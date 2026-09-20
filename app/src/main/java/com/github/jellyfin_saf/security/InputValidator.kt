package com.github.jellyfin_saf.security

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.regex.Pattern

/**
 * Strict boundary validation for external inputs: URLs, UUIDs, and file paths.
 * Guards against injection, path traversal, and malformed input.
 */
object InputValidator {

    private val UUID_REGEX = Pattern.compile(
        "^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /**
     * Validates if a string is a well-formed UUID.
     * Prevents injection attacks into API queries and file paths.
     */
    fun isValidUuid(uuidString: String?): Boolean {
        if (uuidString.isNullOrBlank()) return false
        return UUID_REGEX.matcher(uuidString.trim()).matches()
    }

    /**
     * Normalizes and validates server URLs.
     * Enforces HTTP or HTTPS protocol; rejects file://, javascript:, or dangerous schemes.
     */
    fun normalizeAndValidateServerUrl(inputUrl: String): String {
        var trimmed = inputUrl.trim().removeSuffix("/")
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Server URL cannot be empty")
        }

        if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Only HTTP and HTTPS protocols are permitted. Found: $scheme")
            }
        } else {
            trimmed = "http://$trimmed"
        }

        val uri = try {
            URI.create(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid server URL format: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Only HTTP and HTTPS protocols are permitted. Found: $scheme")
        }

        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Server URL must include a valid host")
        }

        return trimmed
    }

    /**
     * Path traversal defense: guarantees that the resulting file resides
     * strictly within [baseDir]. Throws [SecurityException] if traversal
     * (e.g. using `../`) is attempted.
     */
    @Throws(SecurityException::class, IOException::class)
    fun getSafeFile(baseDir: File, fileName: String): File {
        // Strip null bytes and control chars
        val cleanName = fileName.replace("\u0000", "").trim()
        if (cleanName.isEmpty() || cleanName.contains("..") || cleanName.contains(File.separator)) {
            throw SecurityException("Potential path traversal detected in file name: $fileName")
        }

        val targetFile = File(baseDir, cleanName)
        val canonicalBase = baseDir.canonicalPath
        val canonicalTarget = targetFile.canonicalPath

        if (!canonicalTarget.startsWith(canonicalBase + File.separator) && canonicalTarget != canonicalBase) {
            throw SecurityException("Path traversal violation: $canonicalTarget is outside $canonicalBase")
        }

        return targetFile
    }

    /**
     * Sanitizes file names to prevent path traversal and shell injection.
     */
    fun sanitizeFileName(input: String): String {
        val simple = File(input).name
        return simple.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }
}
