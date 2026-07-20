package com.miruronative.data.reminder

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fetches a snapshot concurrently but never exposes a partial result after an exception.
 * Destructive reconciliation must only run after every input has completed successfully.
 */
internal suspend fun <Input, Output : Any> fetchCompleteSnapshot(
    inputs: Iterable<Input>,
    fetch: suspend (Input) -> Output?,
): List<Output> = coroutineScope {
    inputs
        .map { input -> async { fetch(input) } }
        .awaitAll()
        .filterNotNull()
}
