package kr.hirest.velocityplayersync

import kr.hirest.velocityplayersync.config.DuplicateLoginAction
import kr.hirest.velocityplayersync.config.DuplicateLoginSettings
import kr.hirest.velocityplayersync.config.MaxPlayersMode
import kr.hirest.velocityplayersync.config.PingSettings
import kr.hirest.velocityplayersync.config.PluginConfig
import kr.hirest.velocityplayersync.config.RedisFailureMode
import kr.hirest.velocityplayersync.config.RedisSettings
import kr.hirest.velocityplayersync.config.SyncSettings
import kr.hirest.velocityplayersync.config.TabGhostCleanupSettings
import kr.hirest.velocityplayersync.model.GlobalPlayer
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import kotlin.test.fail

fun testConfig(
    proxyId: String,
    redisPort: Int,
    duplicateLogin: DuplicateLoginSettings = DuplicateLoginSettings(true, DuplicateLoginAction.KICK_OLD),
    heartbeatMs: Long = 300,
) = PluginConfig(
    proxyId = proxyId,
    redis = RedisSettings("redis://127.0.0.1:$redisPort/0", "vpstest", 2000),
    // 테스트가 빨리 끝나도록 실제 기본값(5s/15s/10s)보다 훨씬 짧게 잡는다.
    sync = SyncSettings(
        heartbeatInterval = Duration.ofMillis(heartbeatMs),
        proxyTimeout = Duration.ofMillis(maxOf(1500, heartbeatMs * 2)),
        fullResyncInterval = Duration.ofMillis(500),
    ),
    ping = PingSettings(true, MaxPlayersMode.KEEP, 500, 1, true, 12, RedisFailureMode.LOCAL_ONLY),
    duplicateLogin = duplicateLogin,
    tabGhostCleanup = TabGhostCleanupSettings(true, TAB_CHANNEL),
    messages = emptyMap(),
)

const val TAB_CHANNEL = "TAB_4_test"

fun player(name: String, proxyId: String, server: String? = null, vanished: Boolean = false, joinedAt: Long = 1000L) =
    GlobalPlayer(uuidOf(name), name, proxyId, server, joinedAt, vanished)

/** 이름마다 항상 같은 UUID */
fun uuidOf(name: String): UUID = UUID.nameUUIDFromBytes("test:$name".toByteArray())

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** [condition] 이 참이 될 때까지 기다린다. */
fun eventually(timeoutMs: Long = 5000, message: () -> String = { "조건을 만족하지 못했습니다" }, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (runCatching(condition).getOrDefault(false)) return
        Thread.sleep(50)
    }
    if (!condition()) fail("${timeoutMs}ms 안에 " + message())
}
