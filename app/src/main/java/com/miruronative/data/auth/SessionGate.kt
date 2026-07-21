package com.miruronative.data.auth

/**
 * Serializes authentication mutations and invalidates work started by an older session.
 * The generation check and its mutation deliberately happen under the same monitor so logout
 * cannot land between a stale request's check and commit.
 */
internal class SessionGate {
    internal data class Snapshot<out T>(
        internal val generation: Long,
        val value: T,
    )

    private val lock = Any()
    private var generation = 0L

    fun <T> snapshot(read: () -> T): Snapshot<T> =
        synchronized(lock) { Snapshot(generation, read()) }

    fun generationSnapshot(): Long = synchronized(lock) { generation }

    fun invalidate(change: () -> Unit) {
        synchronized(lock) {
            generation++
            change()
        }
    }

    fun commitIfCurrent(snapshot: Snapshot<*>, change: () -> Unit): Boolean =
        synchronized(lock) {
            if (generation != snapshot.generation) {
                false
            } else {
                change()
                true
            }
        }

    fun commitIfGenerationCurrent(expected: Long, change: () -> Unit): Boolean =
        synchronized(lock) {
            if (generation != expected) {
                false
            } else {
                change()
                true
            }
        }

    fun replaceIfCurrent(snapshot: Snapshot<*>, change: () -> Unit): Boolean =
        synchronized(lock) {
            if (generation != snapshot.generation) {
                false
            } else {
                generation++
                change()
                true
            }
        }

    fun invalidateIfCurrent(snapshot: Snapshot<*>, change: () -> Unit): Boolean =
        replaceIfCurrent(snapshot, change)

    fun isCurrent(snapshot: Snapshot<*>): Boolean =
        synchronized(lock) { generation == snapshot.generation }
}
