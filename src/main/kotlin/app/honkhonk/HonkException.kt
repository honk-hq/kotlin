package app.honkhonk

import java.time.Duration

/** One field the server refused (`error.fields[]`), or the missing message/title found before sending. */
public data class FieldError @JvmOverloads constructor(
    /** Wire name: `group_key`, `metadata.region`, `Idempotency-Key`, `body`, … */
    val field: String,
    /** `required`, `invalid_enum`, `invalid_format`, `invalid_utf8`, `too_long`, … */
    val code: String,
    val message: String? = null,
)

/**
 * Base class of every error thrown by `send`. Unchecked, so Java callers catch only what they
 * care about. Retryable errors ([isRetryable]) can be sent again later with the same
 * [idempotencyKey] without creating duplicates.
 */
public open class HonkException internal constructor(
    message: String,
    /** HTTP status, when the server answered. */
    public val status: Int? = null,
    /** API error code (`invalid_key`, `quota_exceeded`, …) or `network_error` / `timeout` / `validation_failed`. */
    public val code: String? = null,
    /** Server request id (`req_…`). */
    public val requestId: String? = null,
    /** The Idempotency-Key that was used. */
    public val idempotencyKey: String? = null,
    /** HTTP attempts made (0 when rejected locally). */
    public val attempts: Int = 0,
    /** The server's Retry-After, when present. */
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** True when sending the same event again later (same idempotency key) may succeed. */
    public open val isRetryable: Boolean get() = false
}

/**
 * The server refused the message (400, 413, 415, 422: invalid JSON, a body over 16 KiB, an unknown
 * field, an invalid `event_type`, …), or it has neither a message nor a title ([isLocal]: nothing
 * was sent). [fields] lists every refused field; the server corrects everything else and reports
 * it in [Accepted.warnings] instead.
 */
public class HonkValidationException internal constructor(
    message: String,
    /** Every invalid field. */
    public val fields: List<FieldError>,
    /** True when nothing was sent (no message and no title, or an Idempotency-Key a header cannot carry). */
    public val isLocal: Boolean,
    status: Int? = null,
    code: String? = "validation_failed",
    requestId: String? = null,
    idempotencyKey: String? = null,
    attempts: Int = 0,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts) {
    internal companion object {
        fun local(fields: List<FieldError>): HonkValidationException = HonkValidationException(
            "Invalid Honk message: " + fields.joinToString("; ") { "${it.field} ${it.message ?: it.code}" },
            fields,
            isLocal = true,
        )
    }
}

/** 401/403: invalid or revoked key, suspended project or workspace. */
public class HonkAuthException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts)

/** 429 after retries: `quota_exceeded` (daily, until UTC midnight) or `rate_limited`. See [retryAfter]. */
public class HonkQuotaException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int, retryAfter: Duration?,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts, retryAfter) {
    override val isRetryable: Boolean get() = true
}

/** 409 `idempotency_conflict`: the key was already used with a different payload in the last 24 hours. */
public class HonkConflictException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts)

/** Honk could not be reached on any attempt before the deadline. */
public open class HonkNetworkException internal constructor(
    message: String, code: String, idempotencyKey: String?, attempts: Int, cause: Throwable?,
) : HonkException(message, null, code, null, idempotencyKey, attempts, null, cause) {
    override val isRetryable: Boolean get() = true
}

/** Attempts timed out; the message may or may not have been stored (retrying with the same key is safe). */
public class HonkTimeoutException internal constructor(
    message: String, idempotencyKey: String?, attempts: Int, cause: Throwable?,
) : HonkNetworkException(message, "timeout", idempotencyKey, attempts, cause)

/** 5xx on every attempt before the deadline. */
public class HonkServerException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int, retryAfter: Duration?,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts, retryAfter) {
    override val isRetryable: Boolean get() = true
}
