package app.honkhonk

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The request body. The SDK does not check field contents: the server delivers first, corrects
 * what it can and reports each correction in [Accepted.warnings] (contracts/API.md §5.1).
 */
internal object Wire {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Applies defaults and returns the JSON body. Throws only when there is neither a message nor a title. */
    fun encode(m: Message, defaults: Defaults): String {
        fun nonEmpty(s: String?) = s?.takeIf { it.isNotEmpty() }
        if (m.message.isBlank() && m.title.isNullOrBlank()) {
            throw HonkValidationException.local(listOf(FieldError("message", "required", "is required (send a message or a title)")))
        }
        val body = LinkedHashMap<String, Any>()
        nonEmpty(m.title)?.let { body["title"] = it }
        nonEmpty(m.message)?.let { body["message"] = it }
        m.severity?.let { body["severity"] = it.value }
        m.priority?.let { body["priority"] = it.value }
        m.category?.let { body["category"] = it.value }
        (nonEmpty(m.source) ?: nonEmpty(defaults.source))?.let { body["source"] = it }
        (nonEmpty(m.environment) ?: nonEmpty(defaults.environment))?.let { body["environment"] = it }
        (nonEmpty(m.channel) ?: nonEmpty(defaults.channel))?.let { body["channel"] = it }
        nonEmpty(m.groupKey)?.let { body["group_key"] = it }
        m.eventType?.let { body["event_type"] = it.value }
        m.occurredAt?.let { body["occurred_at"] = TIMESTAMP.format(it) }
        nonEmpty(m.url)?.let { body["url"] = it }
        nonEmpty(m.imageUrl)?.let { body["image_url"] = it }
        if (m.actions.isNotEmpty()) body["actions"] = m.actions.map { linkedMapOf("title" to it.title, "url" to it.url) }
        if (m.metadata.isNotEmpty()) body["metadata"] = m.metadata
        m.ttlSeconds?.let { body["ttl_seconds"] = it }
        m.sourceSequence?.let { body["source_sequence"] = it }
        return Json.write(body)
    }

    /**
     * Whether java.net.http sends [value] as a header exactly as given: printable ASCII and
     * spaces. A control character could break the request, `HttpRequest.Builder.header` throws
     * for anything beyond U+00FF, and other non-ASCII characters are not guaranteed to reach the
     * server as given (they could arrive as a different, valid key). The server's own rules (an
     * Idempotency-Key is 1–128 printable ASCII characters, a valid key) come back as its 422 / 401.
     */
    fun sendableHeaderValue(value: String): Boolean = value.all { it.code in 0x20..0x7E }
}
