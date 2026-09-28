package kr.hirest.velocityplayersync.bridge

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * 백엔드 서버용 보조 플러그인.
 *
 * CMI 를 비롯한 대부분의 버니시 플러그인은 버니시 중인 플레이어에 `vanished = true` 메타데이터를 단다.
 * 이 값을 주기적으로 확인해 바뀌었을 때만 플레이어의 연결을 통해 Velocity 의 VelocityPlayerSync 로 보낸다.
 * Redis 설정은 필요 없다.
 */
class VelocityPlayerSyncBridge : JavaPlugin(), Listener {

    /** 마지막으로 보낸 버니시 상태 (메인 스레드에서만 접근) */
    private val lastSent = HashMap<UUID, Boolean>()

    /** 입장 시각. 버니시 플러그인이 입장 직후 상태를 다시 적용할 시간을 준다. */
    private val joinedAt = HashMap<UUID, Long>()

    override fun onEnable() {
        server.messenger.registerOutgoingPluginChannel(this, CHANNEL)
        server.pluginManager.registerEvents(this, this)
        // 리로드로 켜진 경우 이미 접속해 있는 플레이어도 바로 확인한다.
        val now = System.currentTimeMillis()
        server.onlinePlayers.forEach { joinedAt[it.uniqueId] = now - INITIAL_DELAY_MS }
        server.scheduler.runTaskTimer(this, Runnable { check() }, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS)
    }

    override fun onDisable() {
        server.messenger.unregisterOutgoingPluginChannel(this)
        lastSent.clear()
        joinedAt.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        joinedAt[event.player.uniqueId] = System.currentTimeMillis()
        lastSent.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        joinedAt.remove(event.player.uniqueId)
        lastSent.remove(event.player.uniqueId)
    }

    private fun check() {
        val now = System.currentTimeMillis()
        for (player in server.onlinePlayers) {
            val uuid = player.uniqueId
            val joined = joinedAt.getOrPut(uuid) { now }
            if (now - joined < INITIAL_DELAY_MS) continue

            val vanished = isVanished(player)
            if (lastSent[uuid] == vanished) continue
            // Velocity 가 채널을 알려주기 전이면 보내도 버려진다. 다음 확인 때 다시 시도한다.
            if (CHANNEL !in player.listeningPluginChannels) continue

            player.sendPluginMessage(this, CHANNEL, encodeVanish(vanished))
            lastSent[uuid] = vanished
        }
    }

    private fun isVanished(player: Player): Boolean =
        player.getMetadata("vanished").any { it.asBoolean() }

    private fun encodeVanish(vanished: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(PROTOCOL_VERSION)
            out.writeUTF("VANISH")
            out.writeBoolean(vanished)
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val CHANNEL = "velocityplayersync:bridge"
        const val PROTOCOL_VERSION = 1
        const val CHECK_PERIOD_TICKS = 10L
        const val INITIAL_DELAY_MS = 1000L
    }
}
