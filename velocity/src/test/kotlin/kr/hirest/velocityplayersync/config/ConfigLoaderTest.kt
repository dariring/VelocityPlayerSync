package kr.hirest.velocityplayersync.config

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private val logger = LoggerFactory.getLogger("config-test")

    @Test
    fun `default config is copied and parsed`() {
        val dir = Files.createTempDirectory("vps-config")
        val config = ConfigLoader.load(dir, logger)

        assertTrue(Files.exists(dir.resolve("config.yml")))
        assertEquals("proxy-1", config.proxyId)
        assertEquals("redis://localhost:6379/0", config.redis.url)
        assertEquals("vps", config.redis.keyPrefix)
        assertEquals(Duration.ofSeconds(5), config.sync.heartbeatInterval)
        assertEquals(Duration.ofSeconds(15), config.sync.proxyTimeout)
        assertEquals(MaxPlayersMode.KEEP, config.ping.maxPlayersMode)
        assertEquals(DuplicateLoginAction.KICK_OLD, config.duplicateLogin.action)
        assertTrue(config.messages.containsKey("kicked-logged-in-elsewhere"))
        assertTrue(config.messages.containsKey("prefix"))
    }

    @Test
    fun `missing keys fall back to defaults without rewriting the file`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val content = "# 내 주석\nproxy-id: \"proxy-b\"\nping:\n  max-players:\n    mode: fixed\n"
        file.writeText(content)

        val config = ConfigLoader.load(dir, logger)
        assertEquals("proxy-b", config.proxyId)
        assertEquals(MaxPlayersMode.FIXED, config.ping.maxPlayersMode)
        assertEquals(500, config.ping.fixedMaxPlayers)
        assertEquals(Duration.ofSeconds(10), config.sync.fullResyncInterval)
        assertEquals(content, file.readText(), "사용자 파일(주석 포함)은 그대로 둬야 합니다")
    }

    @Test
    fun `invalid values are rejected or corrected`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")

        file.writeText("proxy-id: \"has space\"\n")
        assertFailsWith<ConfigException> { ConfigLoader.load(dir, logger) }

        file.writeText("proxy-id: \"ok\"\nredis:\n  url: \"localhost:6379\"\n")
        assertFailsWith<ConfigException> { ConfigLoader.load(dir, logger) }

        file.writeText(
            "proxy-id: \"ok\"\nsync:\n  heartbeat-interval-seconds: 5\n  proxy-timeout-seconds: 3\n" +
                "duplicate-login:\n  action: SOMETHING\n",
        )
        val config = ConfigLoader.load(dir, logger)
        assertEquals(Duration.ofSeconds(10), config.sync.proxyTimeout, "하트비트의 2배로 올려야 합니다")
        assertEquals(DuplicateLoginAction.KICK_OLD, config.duplicateLogin.action)
    }
}
