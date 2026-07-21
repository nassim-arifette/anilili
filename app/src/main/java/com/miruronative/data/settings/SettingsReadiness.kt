package com.miruronative.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Suspends without polling and preserves structured-concurrency cancellation. */
internal suspend fun awaitSettingsReady(readiness: Flow<Boolean>) {
    readiness.first { it }
}
