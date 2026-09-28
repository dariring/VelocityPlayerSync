package kr.hirest.velocityplayersync.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.server.ServerPing
import kr.hirest.velocityplayersync.VelocityPlayerSync
import kr.hirest.velocityplayersync.config.MaxPlayersMode
import kr.hirest.velocityplayersync.config.RedisFailureMode

/**
 * 서버 목록의 인원수를 네트워크 전체 기준으로 바꾼다.
 *
 * MiniMOTD 는 기본 우선순위(0)에서 `event.ping.onlinePlayers` 를 읽어 `<online_players>` 를 만든다.
 * 그보다 먼저 실행되도록 높은 우선순위를 준다.
 */
class PingListener(private val plugin: VelocityPlayerSync) {

    @Subscribe(priority = EARLY)
    fun onPing(event: ProxyPingEvent) {
        val runtime = plugin.runtime ?: return
        val settings = runtime.config.ping
        if (!settings.enabled) return

        val useNetwork = runtime.sync.isHealthy() || settings.onRedisFailure == RedisFailureMode.LAST_KNOWN
        val players = runtime.registry.all(includeVanished = false, localOnly = !useNetwork)

        val builder = event.ping.asBuilder()
        builder.onlinePlayers(players.size)
        when (settings.maxPlayersMode) {
            MaxPlayersMode.KEEP -> Unit
            MaxPlayersMode.FIXED -> builder.maximumPlayers(settings.fixedMaxPlayers)
            MaxPlayersMode.ONLINE_PLUS -> builder.maximumPlayers(players.size + settings.onlinePlus)
        }
        if (settings.sampleEnabled) {
            builder.clearSamplePlayers()
            if (settings.sampleMaxEntries > 0) {
                builder.samplePlayers(
                    players.shuffled()
                        .take(settings.sampleMaxEntries)
                        .map { ServerPing.SamplePlayer(it.name, it.uuid) },
                )
            }
        }
        event.ping = builder.build()
    }

    private companion object {
        /** PostOrder.EARLY 와 같은 값 */
        const val EARLY: Short = (Short.MAX_VALUE / 2).toShort()
    }
}
