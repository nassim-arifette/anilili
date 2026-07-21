package com.miruronative.ui.watch

import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedProviderControlsScriptTest {
    @Test
    fun `app controls hide known reachable provider bars and native controls`() {
        val script = providerControlsVisibilityJs(hidden = true, navigationGeneration = 7L)

        assertTrue(script.contains("window.__aniliNavigationToken !== '7'"))
        assertTrue(script.contains(".plyr__controls"))
        assertTrue(script.contains(".vjs-control-bar"))
        assertTrue(script.contains(".jw-controlbar"))
        assertTrue(script.contains("pointer-events: none !important"))
        assertTrue(script.contains("video.removeAttribute('controls')"))
        assertTrue(script.contains("frames[i].contentDocument"))
    }

    @Test
    fun `provider controls can be restored without touching cross-origin frames`() {
        val script = providerControlsVisibilityJs(hidden = false, navigationGeneration = 8L)

        assertTrue(script.contains("style.parentNode.removeChild(style)"))
        assertTrue(script.contains("video.__aniliHadNativeControls"))
        assertTrue(script.contains("catch (e) { /* cross-origin */ }"))
    }
}
