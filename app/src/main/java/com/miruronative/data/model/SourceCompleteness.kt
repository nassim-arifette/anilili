package com.miruronative.data.model

/**
 * Explicit completeness signal for data that may legitimately not exist.
 *
 * A nullable value cannot distinguish an authoritative absence from a truncated response or a
 * transient upstream failure. Destructive reconciliation callers must therefore consume this
 * type and abort when [Incomplete] is returned.
 */
sealed interface SourceCompleteness<out T> {
    data class Present<T>(val value: T) : SourceCompleteness<T>

    data object DefinitiveAbsence : SourceCompleteness<Nothing>

    data class Incomplete(
        val reason: String,
        val cause: Throwable? = null,
    ) : SourceCompleteness<Nothing>
}

class IncompleteSourceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun <T> SourceCompleteness<T>.nullableOrThrow(): T? = when (this) {
    is SourceCompleteness.Present -> value
    SourceCompleteness.DefinitiveAbsence -> null
    is SourceCompleteness.Incomplete -> throw IncompleteSourceException(reason, cause)
}
