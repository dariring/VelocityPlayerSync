package kr.hirest.velocityplayersync.listener

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import kr.hirest.velocityplayersync.VelocityPlayerSync
import kr.hirest.velocityplayersync.sync.LoginCheck

class ConnectionListener(private val plugin: VelocityPlayerSync) {

    /**
     * 중복 접속 확인. 밴 플러그인 등이 먼저 거부할 수 있도록 늦게 실행하고,
     * 이미 거부된 접속은 건드리지 않는다.
     */
    @Subscribe(priority = LATE)
    fun onLogin(event: LoginEvent): EventTask? {
        if (!event.result.isAllowed) return null
        val runtime = plugin.runtime ?: return null
        return EventTask.async {
            val check = runtime.sync.checkLogin(event.player.uniqueId)
            if (check is LoginCheck.Denied) {
                event.result = ResultedEvent.ComponentResult.denied(runtime.messages.get(check.messageKey))
            }
        }
    }

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        plugin.runtime?.sync?.onJoin(player.uniqueId, player.username)
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        plugin.runtime?.sync?.onServerSwitch(event.player.uniqueId, event.server.serverInfo.name)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        // 같은 프록시에 같은 계정이 다시 들어와 기존 접속이 끊긴 경우,
        // 기존 접속의 DisconnectEvent 가 새 접속의 PostLoginEvent 보다 늦게 올 수 있다.
        // 그때 지우면 새 접속까지 목록에서 사라지므로 건너뛴다.
        val current = plugin.server.getPlayer(player.uniqueId).orElse(null)
        if (current != null && current !== player) return
        plugin.runtime?.sync?.onQuit(player.uniqueId)
    }

    private companion object {
        /** PostOrder.LATE 와 같은 값 */
        const val LATE: Short = (Short.MIN_VALUE / 2).toShort()
    }
}
