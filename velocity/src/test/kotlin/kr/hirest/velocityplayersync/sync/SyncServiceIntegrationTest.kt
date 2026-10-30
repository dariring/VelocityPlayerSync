package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.TAB_CHANNEL
import kr.hirest.velocityplayersync.config.DuplicateLoginAction
import kr.hirest.velocityplayersync.config.DuplicateLoginSettings
import kr.hirest.velocityplayersync.eventually
import kr.hirest.velocityplayersync.freePort
import kr.hirest.velocityplayersync.testConfig
import kr.hirest.velocityplayersync.uuidOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import redis.embedded.RedisServer
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 내장 Redis 하나에 프록시 여러 대를 띄워 실제 Redis 명령·Pub/Sub 으로 동기화를 확인한다.
 * 프록시 = [Node] (SyncService + PlayerRegistry). Velocity 이벤트 대신 on* 메서드를 직접 부른다.
 */
class SyncServiceIntegrationTest {

    /** 테스트용 프록시 한 대 */
    private inner class Node(
        val id: String,
        duplicateLogin: DuplicateLoginSettings = DuplicateLoginSettings(true, DuplicateLoginAction.KICK_OLD),
        heartbeatMs: Long = 300,
    ) {
        val config = testConfig(id, port, duplicateLogin, heartbeatMs)
        val registry = PlayerRegistry(id)
        val connector = RedisConnector(config.redis)
        val kicked = CopyOnWriteArrayList<UUID>()
        val service: SyncService = SyncService(config, registry, connector, LoggerFactory.getLogger("proxy-$id"), "test") { uuid ->
            // Velocity 에서는 player.disconnect() → DisconnectEvent → onQuit 순서로 이어진다.
            kicked += uuid
            service.onQuit(uuid)
        }

        fun join(name: String, server: String = "lobby") {
            val uuid = uuidOf(name)
            assertEquals(LoginCheck.Allowed, service.checkLogin(uuid), "$name 접속이 막혔습니다")
            service.onJoin(uuid, name)
            service.onServerSwitch(uuid, server)
        }

        fun count(includeVanished: Boolean = false) = registry.count(includeVanished)

        fun stop(graceful: Boolean = true) {
            service.shutdown(graceful)
            connector.close()
            nodes -= this
        }
    }

    private val nodes = CopyOnWriteArrayList<Node>()

    /** TAB 채널로 나간 PlayerQuit 의 대상 UUID 를 모은다. */
    private inner class TabQuitCapture : AutoCloseable {
        val quits = CopyOnWriteArrayList<UUID>()
        private val listener = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                val outer = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(message)))
                outer.readInt()
                outer.readInt()
                val inner = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(outer.readUTF())))
                inner.readUTF()
                if (inner.readUTF() == "PlayerQuit") quits += UUID(inner.readLong(), inner.readLong())
            }
        }
        private val thread = Thread { Jedis("127.0.0.1", port).use { it.subscribe(listener, TAB_CHANNEL) } }.apply { start() }

        init {
            eventually { listener.isSubscribed }
        }

        override fun close() {
            listener.unsubscribe()
            thread.join(2000)
        }
    }

    private fun node(
        id: String,
        duplicateLogin: DuplicateLoginSettings = DuplicateLoginSettings(true, DuplicateLoginAction.KICK_OLD),
        heartbeatMs: Long = 300,
    ) = Node(id, duplicateLogin, heartbeatMs).also {
            nodes += it
            it.service.start()
        }

    @BeforeEach
    fun flush() {
        Jedis("127.0.0.1", port).use { it.flushAll() }
    }

    @AfterEach
    fun stopNodes() {
        nodes.toList().forEach { it.stop() }
    }

    @Test
    fun `30 players on A and 33 on B are 63 on both`() {
        val a = node("a")
        val b = node("b")
        repeat(30) { a.join("a$it") }
        repeat(33) { b.join("b$it") }

        eventually(message = { "A=${a.count()} B=${b.count()}" }) { a.count() == 63 && b.count() == 63 }

        // 서버 이동과 퇴장도 반영된다.
        a.service.onServerSwitch(uuidOf("a0"), "survival")
        eventually { b.registry.find("a0")?.server == "survival" }

        a.service.onQuit(uuidOf("a1"))
        b.service.onQuit(uuidOf("b1"))
        eventually(message = { "A=${a.count()} B=${b.count()}" }) { a.count() == 61 && b.count() == 61 }
    }

    @Test
    fun `a resync interval longer than the proxy timeout stays healthy between resyncs`() {
        val base = testConfig("A", port)
        val config = base.copy(sync = base.sync.copy(fullResyncInterval = Duration.ofMillis(2500)))
        val connector = RedisConnector(config.redis)
        val service = SyncService(config, PlayerRegistry("A"), connector, LoggerFactory.getLogger("proxy-A"), "test") {}
        service.start()
        try {
            eventually { service.isHealthy() }
            Thread.sleep(2000) // proxy-timeout(1500ms)은 지났지만 다음 동기화(2500ms) 전
            assertTrue(service.isHealthy(), "동기화 사이에 비정상으로 판단했습니다")
        } finally {
            service.shutdown()
            connector.close()
        }
    }

    @Test
    fun `a proxy started later receives existing players through full resync`() {
        val a = node("a")
        repeat(5) { a.join("a$it") }
        eventually { Jedis("127.0.0.1", port).use { it.hlen("vpstest:players:a") } == 5L }

        val b = node("b")
        eventually(message = { "B=${b.count()}" }) { b.count() == 5 }
    }

    @Test
    fun `vanished players are not counted on other proxies`() {
        val a = node("a")
        val b = node("b")
        a.join("visible")
        a.join("staff")
        eventually { b.count() == 2 }

        a.service.setVanished(uuidOf("staff"), true)
        eventually(message = { "B=${b.count()}" }) { b.count() == 1 }
        assertEquals(2, b.count(includeVanished = true))

        a.service.setVanished(uuidOf("staff"), false)
        eventually { b.count() == 2 }
    }

    @Test
    fun `vanish state is remembered when the player reconnects through another proxy`() {
        val a = node("a")
        val b = node("b")
        a.join("staff")
        a.service.setVanished(uuidOf("staff"), true)
        eventually { b.registry.find("staff")?.vanished == true }
        a.service.onQuit(uuidOf("staff"))
        eventually { b.count(includeVanished = true) == 0 }

        b.join("staff")
        assertEquals(true, b.registry.local(uuidOf("staff"))?.vanished, "재접속 직후에도 버니시로 봐야 합니다")
        eventually { a.registry.find("staff")?.vanished == true }
    }

    @Test
    fun `graceful shutdown removes the proxy immediately`() {
        val a = node("a")
        val b = node("b")
        repeat(3) { a.join("a$it") }
        eventually { b.count() == 3 }

        a.stop(graceful = true)
        // PROXY_DOWN 으로 바로 빠진다 (타임아웃 1.5초보다 훨씬 빨리).
        eventually(timeoutMs = 700, message = { "B=${b.count()}" }) { b.count() == 0 }
    }

    @Test
    fun `players of a crashed proxy disappear after the timeout`() {
        val a = node("a")
        val b = node("b")
        repeat(3) { a.join("a$it") }
        eventually { b.count() == 3 }

        a.stop(graceful = false)
        Thread.sleep(500)
        assertEquals(3, b.count(), "타임아웃 전에는 남아 있어야 합니다")
        eventually(timeoutMs = 4000, message = { "B=${b.count()}" }) { b.count() == 0 }
        eventually { b.service.proxies().none { it.id == "a" } }
    }

    @Test
    fun `TAB ghosts of a crashed proxy are removed exactly once, skipping players who came back`() = TabQuitCapture().use { tab ->
        val quits = tab.quits
        val a = node("a")
        val b = node("b")
        val c = node("c")
        repeat(3) { a.join("a$it") }
        eventually { b.count() == 3 && c.count() == 3 }

        a.stop(graceful = false)
        // a2 는 a 가 꺼지자마자 c 로 다시 들어왔다.
        c.service.onJoin(uuidOf("a2"), "a2")
        eventually(timeoutMs = 5000, message = { "quits=$quits" }) { quits.toSet() == setOf(uuidOf("a0"), uuidOf("a1")) }
        Thread.sleep(1500)
        assertEquals(2, quits.size, "b 와 c 가 모두 알아채도 한 번씩만 보내야 합니다: $quits")
    }

    @Test
    fun `Redis losing its data does not make live players TAB ghosts`() {
        val a = node("a")
        // b 는 하트비트가 느려, 데이터가 사라진 뒤 a 가 먼저 b 를 "사라진 프록시"로 보게 된다.
        val b = node("b", heartbeatMs = 3000)
        repeat(3) { b.join("b$it") }
        eventually { a.count() == 3 }
        Thread.sleep(3500) // 첫 하트비트 이후 상태에서 시작

        Jedis("127.0.0.1", port).use { it.flushAll() }
        eventually(timeoutMs = 3000) { a.count() == 0 } // a 는 b 를 잠깐 잃는다
        eventually(timeoutMs = 8000) { a.count() == 3 } // b 가 다시 기록하면 돌아온다

        Jedis("127.0.0.1", port).use { assertTrue(it.keys("vpstest:tab-quit:*").isEmpty(), "살아 있는 플레이어를 TAB 에서 지우면 안 됩니다") }
    }

    @Test
    fun `a player leaving sends one TAB quit from their own proxy`() = TabQuitCapture().use { tab ->
        val a = node("a")
        val b = node("b")
        a.join("Steve")
        eventually { b.count() == 1 }

        a.service.onQuit(uuidOf("Steve"))
        eventually { tab.quits.contains(uuidOf("Steve")) }
        Thread.sleep(1000)
        assertEquals(listOf(uuidOf("Steve")), tab.quits.toList())
    }

    @Test
    fun `stopping the proxy tells TAB about players still online, reload does not`() = TabQuitCapture().use { tab ->
        val a = node("a")
        val b = node("b")
        repeat(3) { a.join("a$it") }
        b.join("stays")
        eventually { b.count() == 4 }

        // 리로드: 플레이어는 그대로 접속해 있으므로 TAB 에 퇴장을 알리면 안 된다.
        a.service.shutdown(graceful = true, proxyStopping = false)
        Thread.sleep(1000)
        assertTrue(tab.quits.isEmpty(), "리로드에는 보내지 않아야 합니다: ${tab.quits}")

        // 프록시 종료: 남은 접속자 전부
        b.service.shutdown(graceful = true, proxyStopping = true)
        eventually { tab.quits.toSet() == setOf(uuidOf("stays")) }
        a.connector.close()
        b.connector.close()
        nodes.clear()
    }

    @Test
    fun `duplicate login kicks the old session on the other proxy`() {
        val a = node("a")
        val b = node("b")
        a.join("Steve")
        eventually { b.count() == 1 }

        val started = System.currentTimeMillis()
        assertEquals(LoginCheck.Allowed, b.service.checkLogin(uuidOf("Steve")))
        assertTrue(System.currentTimeMillis() - started < SyncService.KICK_WAIT_MS, "기존 접속이 끊길 때까지만 기다려야 합니다")
        assertEquals(listOf(uuidOf("Steve")), a.kicked)
        assertEquals(null, a.registry.local(uuidOf("Steve")))

        b.service.onJoin(uuidOf("Steve"), "Steve")
        eventually { a.registry.find("Steve")?.proxyId == "b" }
        assertEquals(1, a.count())
    }

    @Test
    fun `duplicate login can deny the new session instead`() {
        val a = node("a")
        val b = node("b", DuplicateLoginSettings(true, DuplicateLoginAction.DENY_NEW))
        a.join("Steve")
        eventually { b.count() == 1 }

        assertEquals(LoginCheck.Denied("duplicate-login"), b.service.checkLogin(uuidOf("Steve")))
        assertTrue(a.kicked.isEmpty())
        assertNotNull(a.registry.local(uuidOf("Steve")))
    }

    @Test
    fun `simultaneous logins on two proxies let only one through`() {
        val a = node("a")
        val b = node("b")
        val uuid = uuidOf("Steve")

        assertEquals(LoginCheck.Allowed, b.service.checkLogin(uuid))
        // b 가 아직 입장을 마치지 않은 사이 a 로 같은 계정이 들어온다.
        assertEquals(LoginCheck.Denied("duplicate-login-in-progress"), a.service.checkLogin(uuid))

        // b 입장이 끝나면 잠금이 풀리고, 이후 a 접속은 b 의 접속을 끊는다.
        b.service.onJoin(uuid, "Steve")
        eventually { Jedis("127.0.0.1", port).use { !it.exists("vpstest:lock:$uuid") } }
        assertEquals(LoginCheck.Allowed, a.service.checkLogin(uuid))
        assertEquals(listOf(uuid), b.kicked)
    }

    @Test
    fun `login that never completes releases its lock on disconnect`() {
        val a = node("a")
        val uuid = uuidOf("Steve")
        assertEquals(LoginCheck.Allowed, a.service.checkLogin(uuid))
        a.service.onQuit(uuid)
        eventually { Jedis("127.0.0.1", port).use { !it.exists("vpstest:lock:$uuid") } }
    }

    @Test
    fun `second proxy with the same id stays passive until the first one is gone`() {
        val first = node("a")
        val observer = node("b")
        first.join("fromFirst")
        eventually { observer.count() == 1 }

        val second = node("a")
        eventually { second.service.inConflict }
        second.join("fromSecond")
        Thread.sleep(800)
        assertFalse(first.service.inConflict)
        assertEquals(null, observer.registry.find("fromSecond"), "충돌 중에는 공유하지 않아야 합니다")

        // 첫 번째가 비정상 종료되면 두 번째가 이어받는다.
        first.stop(graceful = false)
        eventually(timeoutMs = 5000) { !second.service.inConflict }
        eventually(message = { observer.registry.all(true).toString() }) {
            observer.registry.find("fromSecond") != null && observer.registry.find("fromFirst") == null
        }
    }

    @Test
    fun `recovers after Redis restarts`() {
        val a = node("a")
        val b = node("b")
        a.join("before")
        eventually { b.count() == 1 }

        redis.stop()
        a.join("duringOutage")
        eventually(timeoutMs = 4000) { !b.service.isHealthy() }

        redis.start()
        // Redis 가 비어서 돌아와도 각 프록시가 자기 상태를 다시 쓰고, 구독을 다시 연결한다.
        eventually(timeoutMs = 15_000, message = { "B=${b.registry.all(true)}" }) {
            b.count() == 2 && b.service.isHealthy()
        }
        a.join("afterRecovery")
        eventually(timeoutMs = 5000) { b.count() == 3 }

        // Redis 가 비어서 돌아온 직후 서로가 잠깐 사라져 보였지만, 살아 있는 플레이어를 TAB 에서 지우면 안 된다.
        Thread.sleep(1000)
        Jedis("127.0.0.1", port).use { assertTrue(it.keys("vpstest:tab-quit:*").isEmpty(), "TAB 정리가 실행되면 안 됩니다") }
    }

    companion object {
        private val port = freePort()
        private lateinit var redis: RedisServer

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            redis = RedisServer.newRedisServer().port(port).setting("bind 127.0.0.1").setting("save \"\"").build()
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            redis.stop()
        }
    }
}
