package com.github.jellyfin_saf.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TrackEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        private const val DB_NAME = "jellyfin_audio_cache.db"

        private fun recreateTableV5(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_tracks_grouping")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS tracks_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    albumId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    albumArtist TEXT,
                    composer TEXT,
                    genre TEXT,
                    durationMs INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    mimeType TEXT NOT NULL,
                    year INTEGER,
                    trackNumber INTEGER,
                    discNumber INTEGER,
                    isFavorite INTEGER NOT NULL,
                    lastAccessedAt INTEGER NOT NULL,
                    isFullyCached INTEGER NOT NULL,
                    cachedBytes INTEGER NOT NULL,
                    path TEXT,
                    relativeDir TEXT NOT NULL DEFAULT '',
                    fileName TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())

            val cursor = db.query("PRAGMA table_info(tracks)")
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex >= 0) columns.add(cursor.getString(nameIndex))
            }
            cursor.close()

            val hasPath = columns.contains("path")
            val pathCol = if (hasPath) "path" else "NULL as path"

            db.execSQL("""
                INSERT INTO tracks_new (
                    id, albumId, title, artist, album, albumArtist, composer, genre,
                    durationMs, sizeBytes, mimeType, year, trackNumber, discNumber,
                    isFavorite, lastAccessedAt, isFullyCached, cachedBytes, path
                )
                SELECT
                    id, albumId, title, artist, album, albumArtist, composer, genre,
                    durationMs, sizeBytes, mimeType, year, trackNumber, discNumber,
                    isFavorite, lastAccessedAt, isFullyCached, cachedBytes, $pathCol
                FROM tracks
            """.trimIndent())
            db.execSQL("DROP TABLE tracks")
            db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_albumId ON tracks(albumId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_albumArtist ON tracks(albumArtist)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_relativeDir ON tracks(relativeDir)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_lastAccessedAt ON tracks(lastAccessedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_isFullyCached ON tracks(isFullyCached)")
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateTableV5(db)
            }
        }

        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateTableV5(db)
            }
        }

        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateTableV5(db)
            }
        }

        val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                recreateTableV5(db)
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_3_5, MIGRATION_2_5, MIGRATION_1_5)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
