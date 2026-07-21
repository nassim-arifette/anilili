package com.miruronative.data.reminder

import com.miruronative.data.model.IncompleteSourceException
import com.miruronative.data.model.SourceCompleteness
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class CompleteReleaseSnapshot<out T>(
    val present: List<T>,
    val definitiveAbsenceCount: Int,
    val inputCount: Int,
)

/**
 * Fetches a snapshot concurrently but never exposes a partial result after an exception.
 * Destructive reconciliation must only run after every input has completed successfully.
 */
internal suspend fun <Input, Output : Any> fetchCompleteSnapshot(
    inputs: Iterable<Input>,
    maxConcurrency: Int = DEFAULT_SNAPSHOT_CONCURRENCY,
    fetch: suspend (Input) -> SourceCompleteness<Output>,
): CompleteReleaseSnapshot<Output> = coroutineScope {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    val materializedInputs = inputs.toList()
    val gate = Semaphore(maxConcurrency)
    val signals = materializedInputs
        .map { input -> async { gate.withPermit { fetch(input) } } }
        .awaitAll()

    val incomplete = signals.withIndex().firstNotNullOfOrNull { (index, signal) ->
        (signal as? SourceCompleteness.Incomplete)?.let { index to it }
    }
    if (incomplete != null) {
        val (index, signal) = incomplete
        throw IncompleteSourceException(
            "Release snapshot is incomplete at input $index: ${signal.reason}",
            signal.cause,
        )
    }

    CompleteReleaseSnapshot(
        present = signals.mapNotNull { signal ->
            (signal as? SourceCompleteness.Present)?.value
        },
        definitiveAbsenceCount = signals.count { it === SourceCompleteness.DefinitiveAbsence },
        inputCount = materializedInputs.size,
    )
}

internal const val DEFAULT_SNAPSHOT_CONCURRENCY = 4
