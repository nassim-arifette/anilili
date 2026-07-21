package com.miruronative.data.remote

import kotlinx.coroutines.CompletableDeferred

/**
 * Thread-safe ownership gate for the hidden pipe WebView.
 *
 * A [Session] represents exactly one WebView attachment. Replacing or detaching that attachment
 * makes its readiness signal and every request registered against it terminal, so callbacks from
 * an older WebView can never affect its replacement.
 */
internal class PipeRequestLifecycle<T> {
    internal class Session<T> internal constructor(
        val generation: Long,
    ) {
        internal var readiness = CompletableDeferred<Boolean>()
        internal var readinessState = Readiness.WAITING
        internal var readinessGeneration = 1L
    }

    internal class Request<T> internal constructor(
        val id: String,
        val session: Session<T>,
    ) {
        internal val result = CompletableDeferred<T>()
    }

    internal data class Attachment<T>(
        val session: Session<T>,
        val readinessGeneration: Long,
        val displacedRequests: List<Request<T>>,
    )

    internal data class Navigation<T>(
        val readinessGeneration: Long,
        val displacedRequests: List<Request<T>>,
    )

    internal data class ReadinessSignal<T>(
        val session: Session<T>,
        val generation: Long,
        val result: CompletableDeferred<Boolean>,
    )

    internal enum class Readiness {
        WAITING,
        READY,
        FAILED,
    }

    private val lock = Any()
    private var nextGeneration = 0L
    private var activeSession: Session<T>? = null
    private var nextAttachment = CompletableDeferred<Session<T>>()
    private val pendingRequests = mutableMapOf<String, Request<T>>()

    fun attach(): Attachment<T> = synchronized(lock) {
        activeSession?.failReadiness()
        val displaced = pendingRequests.values.toList()
        pendingRequests.clear()
        val session = Session<T>(generation = ++nextGeneration)
        activeSession = session
        nextAttachment.complete(session)
        Attachment(session, session.readinessGeneration, displaced)
    }

    fun detach(session: Session<T>): List<Request<T>> = synchronized(lock) {
        if (activeSession !== session) return@synchronized emptyList()
        activeSession = null
        nextAttachment = CompletableDeferred()
        session.failReadiness()
        val displaced = pendingRequests.values.toList()
        pendingRequests.clear()
        displaced
    }

    /** Waits for the current or next WebView attachment; caller timeout/cancellation owns the wait. */
    suspend fun awaitSession(): Session<T> {
        val signal = synchronized(lock) {
            activeSession?.let { CompletableDeferred(it) } ?: nextAttachment
        }
        return signal.await()
    }

    /** Starts a fresh main-document generation and drains JS requests owned by the old page. */
    fun beginNavigation(session: Session<T>): Navigation<T>? = synchronized(lock) {
        if (activeSession !== session) return@synchronized null
        session.failReadiness()
        session.readiness = CompletableDeferred()
        session.readinessState = Readiness.WAITING
        val displaced = pendingRequests.values.toList()
        pendingRequests.clear()
        Navigation(++session.readinessGeneration, displaced)
    }

    fun readinessSignal(session: Session<T>): ReadinessSignal<T>? = synchronized(lock) {
        if (activeSession !== session) return@synchronized null
        ReadinessSignal(session, session.readinessGeneration, session.readiness)
    }

    fun markReady(
        session: Session<T>,
        readinessGeneration: Long,
        successful: Boolean,
    ): Boolean = synchronized(lock) {
        if (
            activeSession !== session ||
            session.readinessState != Readiness.WAITING ||
            session.readinessGeneration != readinessGeneration
        ) {
            return@synchronized false
        }
        session.readinessState = if (successful) Readiness.READY else Readiness.FAILED
        session.readiness.complete(successful)
        true
    }

    fun isCurrent(session: Session<T>): Boolean = synchronized(lock) {
        activeSession === session
    }

    fun isCurrent(signal: ReadinessSignal<T>): Boolean = synchronized(lock) {
        activeSession === signal.session &&
            signal.session.readinessGeneration == signal.generation &&
            signal.session.readiness === signal.result
    }

    fun register(signal: ReadinessSignal<T>, id: String): Request<T>? = synchronized(lock) {
        val session = signal.session
        if (
            activeSession !== session ||
            session.readinessState != Readiness.READY ||
            session.readinessGeneration != signal.generation ||
            session.readiness !== signal.result
        ) {
            return@synchronized null
        }
        Request<T>(id, session).also { pendingRequests[id] = it }
    }

    fun take(id: String): Request<T>? = synchronized(lock) {
        pendingRequests.remove(id)
    }

    fun cancel(request: Request<T>): Boolean = synchronized(lock) {
        pendingRequests.remove(request.id, request)
    }

    fun isPending(request: Request<T>): Boolean = synchronized(lock) {
        pendingRequests[request.id] === request
    }

    private fun Session<T>.failReadiness() {
        if (readinessState == Readiness.WAITING) {
            readinessState = Readiness.FAILED
            readiness.complete(false)
        }
    }
}
