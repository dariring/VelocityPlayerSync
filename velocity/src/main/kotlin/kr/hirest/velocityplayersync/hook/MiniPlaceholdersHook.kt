package kr.hirest.velocityplayersync.hook

import io.github.miniplaceholders.api.Expansion
import kr.hirest.velocityplayersync.VelocityPlayerSync
import kr.hirest.velocityplayersync.config.RedisFailureMode
import kr.hirest.velocityplayersync.model.GlobalPlayer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag

/**
 * MiniPlaceholders 확장. 버니시 상태인 플레이어는 세지 않는다.
 *
 * - `<vps_online>`                   전체 인원
 * - `<vps_online_local>`             이 프록시 인원
 * - `<vps_online_server:lobby>`      특정 서버 인원 (모든 프록시 합산)
 * - `<vps_online_proxy:proxy-1>`     특정 프록시 인원
 * - `<vps_proxy_count>`              살아 있는 프록시 수
 * - `<vps_proxy_id>`                 이 프록시의 proxy-id
 *
 * MiniPlaceholders 가 설치돼 있을 때만 이 클래스를 불러온다.
 */
class MiniPlaceholdersHook(private val plugin: VelocityPlayerSync) {

    private var expansion: Expansion? = null

    fun register() {
        val built = Expansion.builder("vps")
            .author("SweetRa1n")
            .version(plugin.version)
            .globalPlaceholder("online") { _, _ -> text(count { true }) }
            .globalPlaceholder("online_local") { _, _ ->
                text(plugin.runtime?.registry?.count(localOnly = true) ?: 0)
            }
            .globalPlaceholder("online_server") { queue, _ ->
                val server = queue.popOr("<vps_online_server:서버이름> 형식으로 써야 합니다.").value()
                text(count { it.server.equals(server, ignoreCase = true) })
            }
            .globalPlaceholder("online_proxy") { queue, _ ->
                val proxy = queue.popOr("<vps_online_proxy:프록시ID> 형식으로 써야 합니다.").value()
                text(count { it.proxyId.equals(proxy, ignoreCase = true) })
            }
            .globalPlaceholder("proxy_count") { _, _ -> text(plugin.runtime?.sync?.proxies()?.size ?: 1) }
            .globalPlaceholder("proxy_id") { _, _ -> text(plugin.runtime?.config?.proxyId ?: "") }
            .build()
        built.register()
        expansion = built
    }

    fun unregister() {
        expansion?.takeIf { it.registered() }?.unregister()
        expansion = null
    }

    private fun count(filter: (GlobalPlayer) -> Boolean): Int {
        val runtime = plugin.runtime ?: return plugin.server.playerCount
        val useNetwork = runtime.sync.isHealthy() ||
            runtime.config.ping.onRedisFailure == RedisFailureMode.LAST_KNOWN
        return runtime.registry.all(includeVanished = false, localOnly = !useNetwork).count(filter)
    }

    private fun text(value: Any): Tag = Tag.selfClosingInserting(Component.text(value.toString()))
}
