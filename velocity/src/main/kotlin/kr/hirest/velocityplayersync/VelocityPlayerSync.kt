package kr.hirest.velocityplayersync

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import kr.hirest.velocityplayersync.command.VpsCommand
import kr.hirest.velocityplayersync.config.ConfigException
import kr.hirest.velocityplayersync.config.ConfigLoader
import kr.hirest.velocityplayersync.config.PluginConfig
import kr.hirest.velocityplayersync.hook.MiniPlaceholdersHook
import kr.hirest.velocityplayersync.listener.BridgeListener
import kr.hirest.velocityplayersync.listener.ConnectionListener
import kr.hirest.velocityplayersync.listener.PingListener
import kr.hirest.velocityplayersync.model.GlobalPlayer
import kr.hirest.velocityplayersync.sync.PlayerRegistry
import kr.hirest.velocityplayersync.sync.RedisConnector
import kr.hirest.velocityplayersync.sync.SyncService
import org.slf4j.Logger
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Path
import java.util.UUID

class VelocityPlayerSync @Inject constructor(
    val server: ProxyServer,
    val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    /** 설정과 동기화 서비스 한 벌. 리로드하면 통째로 바뀐다. */
    class Runtime(
        val config: PluginConfig,
        val messages: Messages,
        val sync: SyncService,
        private val redis: RedisConnector,
    ) {
        val registry: PlayerRegistry get() = sync.registry

        /** @param proxyStopping 프록시가 꺼지는 중이면 true, 리로드면 false */
        fun stop(proxyStopping: Boolean) {
            sync.shutdown(proxyStopping = proxyStopping)
            runCatching { redis.close() }
        }
    }

    @Volatile
    var runtime: Runtime? = null
        private set

    /** 설정을 읽지 못해 runtime 이 없을 때도 쓸 수 있는 기본 메시지 */
    val fallbackMessages: Messages by lazy { Messages(loadDefaultMessages()) }

    val version: String by lazy {
        server.pluginManager.fromInstance(this).flatMap { it.description.version }.orElse("unknown")
    }

    private var miniPlaceholdersHook: MiniPlaceholdersHook? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        runtime = createRuntime(previous = emptyMap())

        server.eventManager.register(this, ConnectionListener(this))
        server.eventManager.register(this, PingListener(this))
        server.eventManager.register(this, BridgeListener(this))
        server.channelRegistrar.register(BridgeListener.CHANNEL)

        val commandMeta = server.commandManager.metaBuilder("vps").aliases("velocityplayersync").plugin(this).build()
        server.commandManager.register(commandMeta, VpsCommand(this))

        if (server.pluginManager.isLoaded("miniplaceholders")) {
            miniPlaceholdersHook = runCatching { MiniPlaceholdersHook(this).also { it.register() } }
                .onFailure { logger.warn("MiniPlaceholders 확장을 등록하지 못했습니다: {}", it.toString()) }
                .getOrNull()
            if (miniPlaceholdersHook != null) logger.info("MiniPlaceholders 확장(<vps_...>)을 등록했습니다.")
        }
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        runCatching { miniPlaceholdersHook?.unregister() }
        runtime?.stop(proxyStopping = true)
        runtime = null
    }

    /** `/vps reload`. 새 설정을 읽지 못하면 기존 상태를 그대로 둔다. */
    @Synchronized
    fun reload(): Boolean {
        val config = loadConfig() ?: return false
        val redis = createConnector(config) ?: return false
        val old = runtime
        old?.stop(proxyStopping = false)
        // 멈춘 뒤에 읽어야 그 사이에 들어온 입장과 버니시 변경까지 이어받는다.
        val previous = old?.registry?.localPlayers()?.associateBy { it.uuid } ?: emptyMap()
        runtime = createRuntime(previous, config, redis)
        return runtime != null
    }

    private fun createConnector(config: PluginConfig): RedisConnector? = try {
        RedisConnector(config.redis)
    } catch (e: Exception) {
        logger.error("Redis 설정이 올바르지 않습니다 ({}). 동기화를 시작하지 않습니다.", e.message)
        null
    }

    private fun createRuntime(
        previous: Map<UUID, GlobalPlayer>,
        loaded: PluginConfig? = null,
        connector: RedisConnector? = null,
    ): Runtime? {
        val config = loaded ?: loadConfig() ?: return null
        val redis = connector ?: createConnector(config) ?: return null

        val registry = PlayerRegistry(config.proxyId)
        // 리로드할 때 이미 접속해 있던 플레이어를 다시 등록한다.
        for (player in server.allPlayers) {
            val before = previous[player.uniqueId]
            registry.putLocal(
                GlobalPlayer(
                    uuid = player.uniqueId,
                    name = player.username,
                    proxyId = config.proxyId,
                    server = player.currentServer.map { it.serverInfo.name }.orElse(null),
                    joinedAt = before?.joinedAt ?: System.currentTimeMillis(),
                    vanished = before?.vanished ?: false,
                ),
            )
        }

        val messages = Messages(config.messages)
        val sync = SyncService(config, registry, redis, logger, version) { uuid ->
            server.getPlayer(uuid).ifPresent { it.disconnect(messages.get("kicked-logged-in-elsewhere")) }
        }
        sync.start()
        logger.info("proxy-id '{}' 로 동기화를 시작합니다. (key-prefix: {})", config.proxyId, config.redis.keyPrefix)
        return Runtime(config, messages, sync, redis)
    }

    private fun loadConfig(): PluginConfig? = try {
        ConfigLoader.load(dataDirectory, logger)
    } catch (e: ConfigException) {
        logger.error("config.yml 오류: {}", e.message)
        null
    } catch (e: Exception) {
        logger.error("config.yml 을 불러오지 못했습니다", e)
        null
    }

    private fun loadDefaultMessages(): Map<String, String> = runCatching {
        val loader = YamlConfigurationLoader.builder()
            .url(javaClass.getResource("/config.yml"))
            .build()
        loader.load().node("messages").childrenMap()
            .mapNotNull { (k, v) -> v.string?.let { k.toString() to it } }
            .toMap()
    }.getOrDefault(emptyMap())
}
