package com.github.jellyfin_saf.stream

import java.util.ArrayList

/**
 * Thread-safe interval set tracking downloaded byte ranges [start, end).
 * Automatically merges adjacent and overlapping ranges.
 * Guarantees that sparse gaps or zero-holes can never be read.
 */
class IntervalSet {

    data class Interval(var start: Long, var end: Long)

    private val intervals = ArrayList<Interval>()

    @Synchronized
    fun add(start: Long, end: Long) {
        if (start >= end) return
        var s = start
        var e = end

        val it = intervals.iterator()
        while (it.hasNext()) {
            val curr = it.next()
            // If curr touches or overlaps with [s, e]
            if (curr.end >= s && curr.start <= e) {
                s = minOf(s, curr.start)
                e = maxOf(e, curr.end)
                it.remove()
            }
        }
        intervals.add(Interval(s, e))
        intervals.sortBy { it.start }
    }

    @Synchronized
    fun contains(start: Long, end: Long): Boolean {
        if (start >= end) return true
        for (interval in intervals) {
            if (interval.start <= start && interval.end >= end) {
                return true
            }
        }
        return false
    }

    @Synchronized
    fun getAvailableLengthFrom(offset: Long): Long {
        for (interval in intervals) {
            if (interval.start <= offset && interval.end > offset) {
                return interval.end - offset
            }
        }
        return 0L
    }

    @Synchronized
    fun getFirstMissingRange(totalSize: Long): Pair<Long, Long>? {
        if (intervals.isEmpty()) return if (totalSize > 0) Pair(0L, totalSize) else null
        var cursor = 0L
        for (interval in intervals) {
            if (interval.start > cursor) {
                return Pair(cursor, interval.start)
            }
            cursor = maxOf(cursor, interval.end)
        }
        if (totalSize > 0 && cursor < totalSize) {
            return Pair(cursor, totalSize)
        }
        return null
    }

    @Synchronized
    fun totalBytes(): Long = intervals.sumOf { it.end - it.start }

    @Synchronized
    fun getIntervals(): List<Interval> = ArrayList(intervals)

    @Synchronized
    fun clear() {
        intervals.clear()
    }
}
