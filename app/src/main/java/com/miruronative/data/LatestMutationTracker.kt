package com.miruronative.data

import java.util.UUID

internal data class MutationTicket<K>(
    val version: Long,
    val persistenceToken: String,
    val keys: Set<K>,
)

/** Tracks the newest queued mutation independently for each logical key. */
internal class LatestMutationTracker<K> {
    private val lock = Any()
    private var nextVersion = 0L
    private val latestVersions = mutableMapOf<K, Long>()
    private val ticketsByToken = mutableMapOf<String, MutationTicket<K>>()

    fun begin(keys: Collection<K>): MutationTicket<K> = synchronized(lock) {
        require(keys.isNotEmpty())
        val version = ++nextVersion
        val distinctKeys = keys.toSet()
        distinctKeys.forEach { latestVersions[it] = version }
        MutationTicket(version, UUID.randomUUID().toString(), distinctKeys)
            .also { ticketsByToken[it.persistenceToken] = it }
    }

    fun isLatest(ticket: MutationTicket<K>): Boolean = synchronized(lock) {
        ticket.keys.all { latestVersions[it] == ticket.version }
    }

    /** Removes only keys that were not superseded by a newer ticket. */
    fun complete(ticket: MutationTicket<K>) {
        synchronized(lock) {
            ticketsByToken.remove(ticket.persistenceToken)
            ticket.keys.forEach { key ->
                if (latestVersions[key] == ticket.version) latestVersions.remove(key)
            }
        }
    }

    /** Completes the exact mutation acknowledged by durable storage, if it belongs to this run. */
    fun acknowledge(persistenceToken: String) {
        val ticket = synchronized(lock) { ticketsByToken[persistenceToken] } ?: return
        complete(ticket)
    }

    fun pendingKeys(): Set<K> = synchronized(lock) { latestVersions.keys.toSet() }
}
