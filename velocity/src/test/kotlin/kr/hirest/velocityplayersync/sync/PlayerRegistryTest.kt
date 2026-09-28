package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.player
import kr.hirest.velocityplayersync.uuidOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerRegistryTest {

    private val meta = PlayerRegistry.ProxyMeta(0, 0, "test")

    @Test
    fun `counts local and remote players together`() {
        val registry = PlayerRegistry("a")
        repeat(30) { registry.putLocal(player("a$it", "a")) }
        repeat(33) { registry.upsertRemote(player("b$it", "b")) }

        assertEquals(63, registry.count())
        assertEquals(30, registry.count(localOnly = true))
    }

    @Test
    fun `vanished players are only counted when asked`() {
        val registry = PlayerRegistry("a")
        registry.putLocal(player("visible", "a"))
        registry.putLocal(player("hidden", "a", vanished = true))
        registry.upsertRemote(player("remoteHidden", "b", vanished = true))

        assertEquals(1, registry.count(includeVanished = false))
        assertEquals(3, registry.count(includeVanished = true))
    }

    @Test
    fun `same uuid on two proxies is counted once, local wins`() {
        val registry = PlayerRegistry("a")
        registry.putLocal(player("Steve", "a", joinedAt = 1))
        registry.upsertRemote(player("Steve", "b", joinedAt = 999))

        val all = registry.all(includeVanished = true)
        assertEquals(1, all.size)
        assertEquals("a", all.single().proxyId)
    }

    @Test
    fun `same uuid on two remote proxies keeps the newest`() {
        val registry = PlayerRegistry("a")
        registry.upsertRemote(player("Steve", "b", joinedAt = 1))
        registry.upsertRemote(player("Steve", "c", joinedAt = 2))

        assertEquals("c", registry.find("steve")?.proxyId)
    }

    @Test
    fun `late quit from the previous proxy does not remove the player from the new proxy`() {
        val registry = PlayerRegistry("a")
        registry.upsertRemote(player("Steve", "b"))
        // Steve 가 b 에서 c 로 옮겼는데 c 의 JOIN 이 b 의 QUIT 보다 먼저 도착
        registry.upsertRemote(player("Steve", "c"))
        registry.removeRemote("b", uuidOf("Steve"))

        assertEquals("c", registry.find(uuidOf("Steve"))?.proxyId)
    }

    @Test
    fun `stale full resync does not overwrite a newer event`() {
        val registry = PlayerRegistry("a")
        val readStarted = System.nanoTime()
        Thread.sleep(1)
        registry.upsertRemote(player("Late", "b"))

        // 읽기는 이벤트보다 먼저 시작했으므로 결과(빈 목록)를 버린다.
        assertNull(registry.replaceProxy("b", emptyList(), meta, readStarted))
        assertEquals(1, registry.count())

        // 이벤트 뒤에 시작한 읽기는 반영한다.
        assertEquals(listOf("Late"), registry.replaceProxy("b", emptyList(), meta, System.nanoTime())?.map { it.name })
        assertEquals(0, registry.count())
    }

    @Test
    fun `retainProxies removes proxies that disappeared from Redis`() {
        val registry = PlayerRegistry("a")
        val before = System.nanoTime()
        registry.replaceProxy("b", listOf(player("b1", "b")), meta, before)
        registry.replaceProxy("c", listOf(player("c1", "c")), meta, before)

        assertEquals(listOf("c1"), registry.retainProxies(setOf("b"), System.nanoTime()).map { it.name })
        assertEquals(setOf("b"), registry.remoteProxyIds())
        assertNull(registry.find("c1"))
    }

    @Test
    fun `updateLocal reports only real changes`() {
        val registry = PlayerRegistry("a")
        registry.putLocal(player("Steve", "a"))

        assertNull(registry.updateLocal(uuidOf("Steve")) { null })
        assertNull(registry.updateLocal(uuidOf("Steve")) { it.copy() })
        assertEquals("lobby", registry.updateLocal(uuidOf("Steve")) { it.copy(server = "lobby") }?.server)
        assertNull(registry.updateLocal(uuidOf("Nobody")) { it.copy(server = "lobby") })
    }

    @Test
    fun `find by name ignores case`() {
        val registry = PlayerRegistry("a")
        registry.upsertRemote(player("SweetRa1n", "b", server = "survival"))
        assertEquals("survival", registry.find("sweetra1n")?.server)
    }
}
