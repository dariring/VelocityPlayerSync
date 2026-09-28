package kr.hirest.velocityplayersync.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import kr.hirest.velocityplayersync.VelocityPlayerSync
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * 백엔드의 VPS-Bridge 가 보내는 메시지를 받는다.
 *
 * 형식: [byte 프로토콜 버전=1][UTF 종류][종류별 내용]
 * - "VANISH": [boolean 버니시 여부]
 */
class BridgeListener(private val plugin: VelocityPlayerSync) {

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != CHANNEL) return
        // 이 채널의 메시지는 어느 쪽으로도 전달하지 않는다.
        event.result = PluginMessageEvent.ForwardResult.handled()

        // 클라이언트도 같은 채널로 보낼 수 있다. 백엔드 서버가 보낸 것만 믿는다.
        val source = event.source as? ServerConnection ?: return
        val runtime = plugin.runtime ?: return

        try {
            DataInputStream(ByteArrayInputStream(event.data)).use { input ->
                if (input.readByte().toInt() != PROTOCOL_VERSION) return
                when (input.readUTF()) {
                    "VANISH" -> runtime.sync.setVanished(source.player.uniqueId, input.readBoolean())
                }
            }
        } catch (e: Exception) {
            plugin.logger.warn("{} 에서 온 VPS-Bridge 메시지를 읽지 못했습니다: {}", source.serverInfo.name, e.toString())
        }
    }

    companion object {
        val CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.create("velocityplayersync", "bridge")
        const val PROTOCOL_VERSION = 1
    }
}
