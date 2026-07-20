package com.miruronative.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGateTest {

    @Test
    fun `logout invalidates a delayed refresh commit`() {
        val gate = SessionGate()
        var token: String? = "old"
        val refresh = gate.snapshot { token }

        gate.invalidate { token = null }
        val committed = gate.commitIfCurrent(refresh) { token = "late-refresh" }

        assertFalse(committed)
        assertNull(token)
    }

    @Test
    fun `new login invalidates work from the previous session`() {
        val gate = SessionGate()
        var token = "old"
        val oldRefresh = gate.snapshot { token }
        val login = gate.snapshot { Unit }

        assertTrue(gate.replaceIfCurrent(login) { token = "new-login" })
        assertFalse(gate.commitIfCurrent(oldRefresh) { token = "late-refresh" })
        assertEquals("new-login", token)
    }

    @Test
    fun `stale refresh failure cannot clear a newer login`() {
        val gate = SessionGate()
        var token: String? = "old"
        val oldRefresh = gate.snapshot { token }

        gate.invalidate { token = "new-login" }
        val cleared = gate.invalidateIfCurrent(oldRefresh) { token = null }

        assertFalse(cleared)
        assertEquals("new-login", token)
    }

    @Test
    fun `failed old login cannot clear a newer pending attempt`() {
        val gate = SessionGate()
        var pending = "login-a"
        val loginA = gate.snapshot { pending }

        gate.invalidate { pending = "login-b" }
        val cleared = gate.invalidateIfCurrent(loginA) { pending = "" }

        assertFalse(cleared)
        assertEquals("login-b", pending)
    }

    @Test
    fun `current generation can commit`() {
        val gate = SessionGate()
        var token = "old"
        val refresh = gate.snapshot { token }

        assertTrue(gate.commitIfCurrent(refresh) { token = "fresh" })
        assertTrue(gate.isCurrent(refresh))
        assertEquals("fresh", token)
    }
}
