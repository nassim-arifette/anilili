package com.miruronative.ui.watch

internal const val EMBED_COMMAND_TIMEOUT_MS = 2_500L

internal enum class EmbedCommandKind { SEEK, TOGGLE_PLAYBACK, RESUME_PLAYBACK }

internal data class EmbedCommand(
    val id: Long,
    val navigationGeneration: Long,
    val kind: EmbedCommandKind,
    val issuedAtMs: Long,
    val mediaIdentity: EmbedVideoIdentity?,
)

internal data class EmbedCommandAcknowledgement(
    val commandId: Long,
    val navigationGeneration: Long,
    val succeeded: Boolean,
    val positionMs: Long,
    val isPlaying: Boolean,
    val mediaIdentity: EmbedVideoIdentity? = null,
)

internal sealed interface EmbedCommandResolution {
    data class Confirmed(
        val command: EmbedCommand,
        val acknowledgement: EmbedCommandAcknowledgement,
    ) : EmbedCommandResolution

    data class TimedOut(val command: EmbedCommand) : EmbedCommandResolution

    data class Rejected(val command: EmbedCommand) : EmbedCommandResolution

    data object Ignored : EmbedCommandResolution
}

/** Tracks one-shot page commands so stale, duplicate and late acknowledgements cannot update UI. */
internal class EmbedCommandCoordinator(
    private val timeoutMs: Long = EMBED_COMMAND_TIMEOUT_MS,
) {
    private var nextId = 0L
    private val pending = mutableMapOf<Long, EmbedCommand>()

    init {
        require(timeoutMs > 0L)
    }

    @Synchronized
    fun issue(
        navigationGeneration: Long,
        kind: EmbedCommandKind,
        nowMs: Long,
        mediaIdentity: EmbedVideoIdentity? = null,
    ): EmbedCommand {
        pending.entries.removeAll { (_, command) ->
            command.navigationGeneration == navigationGeneration && command.kind == kind
        }
        return EmbedCommand(
            id = ++nextId,
            navigationGeneration = navigationGeneration,
            kind = kind,
            issuedAtMs = nowMs,
            mediaIdentity = mediaIdentity,
        ).also { pending[it.id] = it }
    }

    @Synchronized
    fun acknowledge(
        acknowledgement: EmbedCommandAcknowledgement,
        nowMs: Long,
        activeMediaIdentity: EmbedVideoIdentity? = null,
    ): EmbedCommandResolution {
        val command = pending[acknowledgement.commandId] ?: return EmbedCommandResolution.Ignored
        if (command.navigationGeneration != acknowledgement.navigationGeneration) {
            return EmbedCommandResolution.Ignored
        }
        pending.remove(command.id)
        if (acknowledgement.mediaIdentity != command.mediaIdentity || activeMediaIdentity != command.mediaIdentity) {
            return EmbedCommandResolution.Rejected(command)
        }
        return if (nowMs - command.issuedAtMs > timeoutMs) {
            EmbedCommandResolution.TimedOut(command)
        } else {
            EmbedCommandResolution.Confirmed(command, acknowledgement)
        }
    }

    @Synchronized
    fun timeout(commandId: Long, nowMs: Long): EmbedCommandResolution {
        val command = pending[commandId] ?: return EmbedCommandResolution.Ignored
        if (nowMs - command.issuedAtMs < timeoutMs) return EmbedCommandResolution.Ignored
        pending.remove(commandId)
        return EmbedCommandResolution.TimedOut(command)
    }

    @Synchronized
    fun cancel(commandId: Long): EmbedCommand? = pending.remove(commandId)
}
