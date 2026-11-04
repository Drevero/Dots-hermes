package app.dotshermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRoutingTest {
    private val ash = AgentLink("main", "wss://h/dots/ash/ws", "t1", remoteId = "ash-id")
    private val grant = AgentLink("a-1", "wss://h/dots/grant/ws", "t2", remoteId = "grant-id")
    private val legacy = AgentLink("a-2", "wss://old/dots/ws", "t3", remoteId = null)

    @Test fun `known agent id goes to that agent`() {
        assertEquals(grant, routePush(listOf(ash, grant), "grant-id"))
        assertEquals(ash, routePush(listOf(ash, grant), "ash-id"))
    }

    @Test fun `single agent with an old plugin keeps getting its notifications`() {
        val only = AgentLink("main", "wss://h/dots/ws", "t", remoteId = null)
        assertEquals(only, routePush(listOf(only), null))
        assertEquals(only, routePush(listOf(only), ""))
    }

    @Test fun `push without agent id goes to the only agent that never announced one`() {
        assertEquals(legacy, routePush(listOf(ash, grant, legacy), null))
    }

    @Test fun `push without agent id is dropped when ambiguous`() {
        val other = legacy.copy(id = "a-3")
        assertNull(routePush(listOf(legacy, other), null))
        assertNull(routePush(listOf(ash, grant), null))
    }

    @Test fun `unknown agent id is never routed to an identified agent`() {
        // Agent retiré de l'app : son gateway peut encore pousser, personne ne doit le recevoir.
        assertNull(routePush(listOf(ash, grant), "removed-id"))
    }

    @Test fun `unknown agent id goes to the agent whose gateway was just upgraded`() {
        assertEquals(legacy, routePush(listOf(ash, legacy), "new-id"))
    }

    @Test fun `no agents, no route`() {
        assertNull(routePush(emptyList(), "ash-id"))
        assertNull(routePush(emptyList(), null))
    }
}
