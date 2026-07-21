package com.miruronative.ui.profile

import com.miruronative.data.auth.AccountService
import com.miruronative.data.auth.AccountSessionIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLoadGateTest {
    private val aniListA = AccountSessionIdentity(AccountService.ANILIST, generation = 11)

    @Test
    fun `current request can publish`() {
        val gate = ProfileLoadGate()
        val request = gate.begin(aniListA)
        var published = false

        val committed = gate.commitIfCurrent(request) { published = true }

        assertTrue(committed)
        assertTrue(published)
    }

    @Test
    fun `explicit invalidation rejects a delayed response`() {
        val gate = ProfileLoadGate()
        val request = gate.begin(aniListA)
        var published = false

        gate.invalidate()
        val committed = gate.commitIfCurrent(request) { published = true }

        assertFalse(committed)
        assertFalse(published)
    }

    @Test
    fun `new load owns publication even within the same login`() {
        val gate = ProfileLoadGate()
        val first = gate.begin(aniListA)
        val second = gate.begin(aniListA)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `explicit invalidation rejects non cooperative cancelled work`() {
        val gate = ProfileLoadGate()
        val request = gate.begin(aniListA)

        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }
}
