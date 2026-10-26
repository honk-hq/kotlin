package app.honkhonk

/**
 * Severity, lowest to highest. Every constant has a horn name on the Honk scale; the horn names
 * are aliases of the canonical constants: `Severity.LOUD === Severity.WARNING`.
 *
 * The level alone decides how the push interrupts: INFO and SUCCESS normal, WARNING and ERROR
 * high, CRITICAL time-sensitive (breaks through Focus).
 */
public enum class Severity(public val value: String) {
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
    ;

    /** The horn name: light, beep, loud, long or blast. */
    public val horn: String
        get() = when (this) {
            INFO -> "light"
            SUCCESS -> "beep"
            WARNING -> "loud"
            ERROR -> "long"
            CRITICAL -> "blast"
        }

    public companion object {
        /** A Light message (info). */
        @JvmField public val LIGHT: Severity = INFO

        /** A Beep message (success). */
        @JvmField public val BEEP: Severity = SUCCESS

        /** A Loud message (warning). */
        @JvmField public val LOUD: Severity = WARNING

        /** A Long message (error). */
        @JvmField public val LONG: Severity = ERROR

        /** A Blast message (critical). */
        @JvmField public val BLAST: Severity = CRITICAL

        /** Horn name → canonical severity. */
        @JvmField public val ALIASES: Map<String, Severity> =
            mapOf("light" to INFO, "beep" to SUCCESS, "loud" to WARNING, "long" to ERROR, "blast" to CRITICAL)

        /** A canonical or horn name, case-insensitively: `parseOrNull("LOUD") == WARNING`. */
        @JvmStatic
        public fun parseOrNull(value: String): Severity? {
            val v = value.trim().lowercase()
            return entries.firstOrNull { it.value == v } ?: ALIASES[v]
        }

        /** Like [parseOrNull], but throws [HonkValidationException] for unknown names. */
        @JvmStatic
        public fun parse(value: String): Severity = parseOrNull(value)
            ?: throw HonkValidationException.local(listOf(FieldError("severity", "invalid_enum", SEVERITY_HINT)))

        internal const val SEVERITY_HINT: String =
            "must be one of light (info), beep (success), loud (warning), long (error), blast (critical)"
    }
}

/**
 * Declared priority, stored for compatibility: the level decides how the push interrupts (LOW still
 * makes an INFO, SUCCESS or WARNING message silent). URGENT from a key without "allow urgent" is
 * sent as HIGH, with a `downgraded` warning.
 */
public enum class Priority(public val value: String) {
    LOW("low"), NORMAL("normal"), HIGH("high"), URGENT("urgent");

    public companion object {
        @JvmStatic
        public fun parse(value: String): Priority = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(listOf(FieldError("priority", "invalid_enum", "must be one of low, normal, high, urgent")))
    }
}

/** PROBLEM opens an incident for its group, RECOVERY closes it (the group key is optional; without one they group by title). */
public enum class EventType(public val value: String) {
    EVENT("event"), PROBLEM("problem"), RECOVERY("recovery");

    public companion object {
        @JvmStatic
        public fun parse(value: String): EventType = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(listOf(FieldError("event_type", "invalid_enum", "must be one of event, problem, recovery")))
    }
}

/** Category taxonomy v1. */
public enum class Category(public val value: String) {
    INFRASTRUCTURE("infrastructure"), SECURITY("security"), BACKUPS("backups"), DEPLOYMENTS("deployments"),
    PAYMENTS("payments"), CUSTOMERS("customers"), SALES("sales"), AUTOMATION("automation"), PERSONAL("personal"),
    OTHER("other");

    public companion object {
        @JvmStatic
        public fun parse(value: String): Category = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: throw HonkValidationException.local(
                listOf(FieldError("category", "invalid_enum", "must be one of " + entries.joinToString(", ") { it.value })),
            )
    }
}
