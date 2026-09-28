package kr.hirest.velocityplayersync.config

import org.slf4j.Logger
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ConfigException(message: String) : Exception(message)

object ConfigLoader {

    private val PROXY_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

    /**
     * `config.yml` 을 읽는다. 파일이 없으면 기본 파일을 복사하고,
     * 빠진 항목은 기본값으로 채운다(파일에는 다시 쓰지 않아 주석이 보존된다).
     */
    fun load(dataDirectory: Path, logger: Logger): PluginConfig {
        Files.createDirectories(dataDirectory)
        val file = dataDirectory.resolve("config.yml")
        if (Files.notExists(file)) {
            val stream = ConfigLoader::class.java.getResourceAsStream("/config.yml")
                ?: throw ConfigException("jar 안에 기본 config.yml 이 없습니다.")
            stream.use { Files.copy(it, file) }
        }

        val defaults = YamlConfigurationLoader.builder()
            .url(ConfigLoader::class.java.getResource("/config.yml"))
            .build()
            .load()
        val root = try {
            YamlConfigurationLoader.builder().path(file).build().load()
        } catch (e: Exception) {
            throw ConfigException("config.yml 을 읽을 수 없습니다: ${e.message}")
        }
        root.mergeFrom(defaults)

        return parse(root, logger)
    }

    fun parse(root: ConfigurationNode, logger: Logger): PluginConfig {
        val proxyId = root.node("proxy-id").getString("").trim()
        if (!PROXY_ID_PATTERN.matches(proxyId)) {
            throw ConfigException("proxy-id '$proxyId' 가 올바르지 않습니다. 영문, 숫자, '-', '_' 로 1~32자여야 합니다.")
        }

        val redisNode = root.node("redis")
        val url = redisNode.node("url").getString("").trim()
        if (!url.startsWith("redis://") && !url.startsWith("rediss://")) {
            throw ConfigException("redis.url 은 redis:// 또는 rediss:// 로 시작해야 합니다.")
        }
        val keyPrefix = redisNode.node("key-prefix").getString("vps").trim().ifEmpty { "vps" }
        val timeoutMs = redisNode.node("timeout-ms").getInt(2000).coerceIn(100, 60_000)

        val syncNode = root.node("sync")
        val heartbeat = syncNode.node("heartbeat-interval-seconds").getLong(5).coerceAtLeast(1)
        var timeout = syncNode.node("proxy-timeout-seconds").getLong(15)
        if (timeout < heartbeat * 2) {
            logger.warn("sync.proxy-timeout-seconds({}) 가 하트비트 간격의 2배보다 작아 {}초로 조정합니다.", timeout, heartbeat * 2)
            timeout = heartbeat * 2
        }
        val resync = syncNode.node("full-resync-interval-seconds").getLong(10).coerceAtLeast(1)

        val pingNode = root.node("ping")
        val maxNode = pingNode.node("max-players")
        val sampleNode = pingNode.node("sample")
        val ping = PingSettings(
            enabled = pingNode.node("enabled").getBoolean(true),
            maxPlayersMode = enumValue(maxNode.node("mode"), MaxPlayersMode.KEEP, logger),
            fixedMaxPlayers = maxNode.node("fixed").getInt(500).coerceAtLeast(0),
            onlinePlus = maxNode.node("online-plus").getInt(1).coerceAtLeast(0),
            sampleEnabled = sampleNode.node("enabled").getBoolean(true),
            sampleMaxEntries = sampleNode.node("max-entries").getInt(12).coerceIn(0, 100),
            onRedisFailure = enumValue(pingNode.node("on-redis-failure"), RedisFailureMode.LOCAL_ONLY, logger),
        )

        val dupNode = root.node("duplicate-login")
        val duplicateLogin = DuplicateLoginSettings(
            enabled = dupNode.node("enabled").getBoolean(true),
            action = enumValue(dupNode.node("action"), DuplicateLoginAction.KICK_OLD, logger),
        )

        val tabNode = root.node("tab-ghost-cleanup")
        val tabGhostCleanup = TabGhostCleanupSettings(
            enabled = tabNode.node("enabled").getBoolean(true),
            channel = tabNode.node("channel").getString("TAB_4_custom").trim().ifEmpty { "TAB_4_custom" },
        )

        val messages = root.node("messages").childrenMap()
            .mapNotNull { (key, node) -> node.string?.let { key.toString() to it } }
            .toMap()

        return PluginConfig(
            proxyId = proxyId,
            redis = RedisSettings(url, keyPrefix, timeoutMs),
            sync = SyncSettings(
                heartbeatInterval = Duration.ofSeconds(heartbeat),
                proxyTimeout = Duration.ofSeconds(timeout),
                fullResyncInterval = Duration.ofSeconds(resync),
            ),
            ping = ping,
            duplicateLogin = duplicateLogin,
            tabGhostCleanup = tabGhostCleanup,
            messages = messages,
        )
    }

    private inline fun <reified E : Enum<E>> enumValue(node: ConfigurationNode, default: E, logger: Logger): E {
        val raw = node.getString(default.name).trim().uppercase()
        return enumValues<E>().firstOrNull { it.name == raw } ?: run {
            val path = node.path().joinToString(".")
            logger.warn("{} 의 값 '{}' 을(를) 알 수 없어 {} 로 설정합니다. 가능한 값: {}",
                path, raw, default.name, enumValues<E>().joinToString { it.name })
            default
        }
    }
}
