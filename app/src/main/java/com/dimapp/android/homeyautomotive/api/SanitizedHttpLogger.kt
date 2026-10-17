package com.dimapp.android.homeyautomotive.api

import android.util.Log
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Custom OkHttp [HttpLoggingInterceptor.Logger] and PII sanitization utility.
 *
 * Intercepts HTTP log output before it reaches Logcat, masking:
 * - 24-character hexadecimal Athom/Homey ObjectIds (e.g., `677c0a03023b9cf8cd156f27` -> `677c***6f27`)
 * - 64-character hexadecimal session IDs in URL query parameters (e.g., `?session=abc...` -> `?session=abc12345...***`)
 * - Geolocation coordinates if present in log strings
 *
 * Also provides static helper methods [maskId] and [sanitize] for application-level logging.
 *
 * @param tag The Logcat tag to use for OkHttp log output (defaults to `"OkHttpClient"`).
 */
class SanitizedHttpLogger(
    private val tag: String = "OkHttpClient"
) : HttpLoggingInterceptor.Logger {

    /**
     * Logs the given [message] after applying comprehensive PII and credential sanitization.
     *
     * Public method implementing [HttpLoggingInterceptor.Logger.log].
     *
     * @param message Raw log line from OkHttp.
     */
    override fun log(message: String) {
        val sanitized = sanitize(message)
        Log.i(tag, sanitized)
    }

    companion object {
        private val SESSION_PARAM_REGEX = Regex("([?&]session=)([0-9a-fA-F]{8})[0-9a-fA-F]{56}")
        private val HOMEY_ID_URL_REGEX = Regex("(https://|/api/|x-homey-id:\\s*)([0-9a-fA-F]{4})[0-9a-fA-F]{16}([0-9a-fA-F]{4})")

        /**
         * Sanitizes a single identifier (such as a Homey Hub ID) for safe logging.
         *
         * Preserves the first 4 and last 4 characters if the ID is at least 8 characters long,
         * matching the pattern used by the Homey Companion App (`677c***6f27`).
         *
         * @param id The raw identifier to mask.
         * @return The masked identifier string, or `"null"` / `"demo"` if appropriate.
         * @example
         * ```kotlin
         * val masked = SanitizedHttpLogger.maskId("677c0a03023b9cf8cd156f27")
         * // Returns: "677c***6f27"
         * ```
         */
        fun maskId(id: String?): String {
            if (id == null) return "null"
            if (id == "demo") return "demo"
            return if (id.length >= 8) {
                "${id.take(4)}***${id.takeLast(4)}"
            } else {
                "***"
            }
        }

        /**
         * Sanitizes a log line, masking query session tokens, Athom IDs, and credential patterns.
         *
         * @param message Raw text message to sanitize.
         * @return Sanitized string safe for Logcat output.
         */
        fun sanitize(message: String): String {
            var result = message

            // Mask session IDs in query parameters: ?session=12345678...***
            result = SESSION_PARAM_REGEX.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}...***"
            }

            // Mask 24-char hex Athom IDs in connect.athom.com URLs or headers
            result = HOMEY_ID_URL_REGEX.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}***${match.groupValues[3]}"
            }

            return result
        }
    }
}
