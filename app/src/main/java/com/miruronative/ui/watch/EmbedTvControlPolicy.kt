package com.miruronative.ui.watch

/** The surface that currently owns Android TV directional/select input for an embed. */
internal enum class EmbedTvRemoteOwner {
    /** The inline player is inactive; the episode/source screen keeps the remote. */
    PARENT,

    /** AniLili+ can identify and command the exact content video. */
    APP_TRANSPORT,

    /** AniLili+ presents the explicit action that transfers focus into the provider page. */
    APP_PROVIDER_HANDOFF,

    /** The cross-origin provider page receives D-pad and Select directly. */
    PROVIDER,
}

/**
 * Capability and focus policy for an embedded player on Android TV.
 *
 * Declaring a managed embed is not enough to expose playback controls: Chromium may keep the real
 * video in a cross-origin iframe. App transport, seeking, and automation are available only after
 * authenticated bridge telemetry proves that the active media can also be commanded.
 */
internal data class EmbedTvControlPolicy(
    val remoteOwner: EmbedTvRemoteOwner,
    val showsAppTransport: Boolean,
    val showsProviderHandoff: Boolean,
    val showsSettings: Boolean,
    val showsFullscreen: Boolean,
    val allowsAppPlaybackCommands: Boolean,
    val allowsSeeking: Boolean,
    val allowsPlaybackAutomation: Boolean,
    val consumesDirectionalOrSelectForApp: Boolean,
    val passesDirectionalOrSelectToProvider: Boolean,
    val recoversAppUiOnBack: Boolean,
)

internal fun embedTvControlPolicy(
    playerOwnsRemote: Boolean,
    managedControlsDeclared: Boolean,
    bridgeAvailable: Boolean,
    providerControlsMode: Boolean,
    automationSupported: Boolean,
    settingsSupported: Boolean,
    fullscreenSupported: Boolean,
): EmbedTvControlPolicy {
    val owner = when {
        !playerOwnsRemote -> EmbedTvRemoteOwner.PARENT
        !managedControlsDeclared -> EmbedTvRemoteOwner.PROVIDER
        bridgeAvailable -> EmbedTvRemoteOwner.APP_TRANSPORT
        providerControlsMode -> EmbedTvRemoteOwner.PROVIDER
        else -> EmbedTvRemoteOwner.APP_PROVIDER_HANDOFF
    }
    val appOwnsChrome = owner == EmbedTvRemoteOwner.APP_TRANSPORT ||
        owner == EmbedTvRemoteOwner.APP_PROVIDER_HANDOFF
    return EmbedTvControlPolicy(
        remoteOwner = owner,
        showsAppTransport = owner == EmbedTvRemoteOwner.APP_TRANSPORT,
        showsProviderHandoff = owner == EmbedTvRemoteOwner.APP_PROVIDER_HANDOFF,
        showsSettings = appOwnsChrome && settingsSupported,
        showsFullscreen = appOwnsChrome && fullscreenSupported,
        allowsAppPlaybackCommands = owner == EmbedTvRemoteOwner.APP_TRANSPORT,
        allowsSeeking = owner == EmbedTvRemoteOwner.APP_TRANSPORT,
        allowsPlaybackAutomation = owner == EmbedTvRemoteOwner.APP_TRANSPORT && automationSupported,
        consumesDirectionalOrSelectForApp = appOwnsChrome,
        passesDirectionalOrSelectToProvider = owner == EmbedTvRemoteOwner.PROVIDER,
        // Unmanaged generic pages keep the normal Activity Back behavior. Only an explicit handoff
        // consumes the first Back press so the viewer can recover AniLili+'s actions safely.
        recoversAppUiOnBack = owner == EmbedTvRemoteOwner.PROVIDER && managedControlsDeclared,
    )
}
