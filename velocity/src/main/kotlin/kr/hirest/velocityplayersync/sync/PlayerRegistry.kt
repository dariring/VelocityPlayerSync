package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.model.GlobalPlayer
import kr.hirest.velocityplayersync.model.ProxyInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 네트워크 전체 접속자의 메모리 캐시.
 *
 * 핑·플레이스홀더·명령어는 이 캐시만 읽는다(Redis 호출 없음).
 * 이 프록시 접속자([local])와 다른 프록시 접속자([remote])를 따로 들고,
 * 다른 프록시 접속자는 프록시별로 나눠 둔다. 그래서 A 에서 B 로 옮긴 플레이어의
 * A 쪽 QUIT 이 B 쪽 JOIN 보다 늦게 도착해도 A 의 목록에서만 빠진다.
 */
class PlayerRegistry(val selfId: String) {

    data class ProxyMeta(val startedAt: Long, val lastHeartbeat: Long, val version: String)

    private val local = ConcurrentHashMap<UUID, GlobalPlayer>()
    private val remote = ConcurrentHashMap<String, ConcurrentHashMap<UUID, GlobalPlayer>>()
    private val remoteMeta = ConcurrentHashMap<String, ProxyMeta>()

    /** 프록시별 마지막 실시간 이벤트 시각(System.nanoTime). 오래된 전체 동기화 결과가 최신 이벤트를 덮지 않게 한다. */
    private val lastEventNanos = HashMap<String, Long>()
    private val remoteLock = Any()

    // ─── 이 프록시 ───────────────────────────────────────────────

    fun local(uuid: UUID): GlobalPlayer? = local[uuid]

    fun localPlayers(): List<GlobalPlayer> = local.values.toList()

    fun putLocal(player: GlobalPlayer) {
        local[player.uuid] = player
    }

    fun removeLocal(uuid: UUID): GlobalPlayer? = local.remove(uuid)

    /** [transform] 이 null 을 돌려주면 바꾸지 않는다. 바뀐 경우에만 새 값을 돌려준다. */
    fun updateLocal(uuid: UUID, transform: (GlobalPlayer) -> GlobalPlayer?): GlobalPlayer? {
        var updated: GlobalPlayer? = null
        local.computeIfPresent(uuid) { _, current ->
            val next = transform(current)
            if (next != null && next != current) {
                updated = next
                next
            } else {
                current
            }
        }
        return updated
    }

    // ─── 다른 프록시 (실시간 이벤트) ─────────────────────────────

    fun upsertRemote(player: GlobalPlayer) {
        if (player.proxyId == selfId) return
        synchronized(remoteLock) {
            markEvent(player.proxyId)
            remote.computeIfAbsent(player.proxyId) { ConcurrentHashMap() }[player.uuid] = player
        }
    }

    fun removeRemote(proxyId: String, uuid: UUID) {
        synchronized(remoteLock) {
            markEvent(proxyId)
            remote[proxyId]?.remove(uuid)
        }
    }

    fun removeProxy(proxyId: String) {
        synchronized(remoteLock) {
            markEvent(proxyId)
            remote.remove(proxyId)
            remoteMeta.remove(proxyId)
        }
    }

    // ─── 다른 프록시 (전체 동기화) ───────────────────────────────

    /**
     * 전체 동기화로 읽은 [proxyId] 의 목록으로 교체한다.
     * 읽기를 시작한 뒤([readStartedNanos]) 그 프록시의 실시간 이벤트가 들어왔다면
     * 읽은 값이 더 오래된 것이므로 이번에는 건너뛴다.
     *
     * @return 퇴장 이벤트 없이 목록에서 빠진 플레이어. 건너뛰었으면 null.
     */
    fun replaceProxy(proxyId: String, players: Collection<GlobalPlayer>, meta: ProxyMeta, readStartedNanos: Long): List<GlobalPlayer>? {
        if (proxyId == selfId) return null
        synchronized(remoteLock) {
            if (hasEventSince(proxyId, readStartedNanos)) return null
            remoteMeta[proxyId] = meta
            val next = ConcurrentHashMap(players.associateBy { it.uuid })
            val previous = remote.put(proxyId, next) ?: return emptyList()
            return previous.values.filter { !next.containsKey(it.uuid) }
        }
    }

    /**
     * [alive] 에 없는 프록시를 지운다. 읽기 시작 뒤 이벤트가 있었던 프록시는 남긴다.
     *
     * @return 지운 프록시에 남아 있던 플레이어 (정상 종료라면 PROXY_DOWN 으로 이미 비어 있다)
     */
    fun retainProxies(alive: Set<String>, readStartedNanos: Long): List<GlobalPlayer> {
        synchronized(remoteLock) {
            val gone = (remote.keys + remoteMeta.keys).filter { it !in alive && !hasEventSince(it, readStartedNanos) }
            val dropped = ArrayList<GlobalPlayer>()
            gone.forEach {
                remote.remove(it)?.let { players -> dropped += players.values }
                remoteMeta.remove(it)
            }
            return dropped
        }
    }

    private fun markEvent(proxyId: String) {
        lastEventNanos[proxyId] = System.nanoTime()
    }

    private fun hasEventSince(proxyId: String, nanos: Long): Boolean {
        val last = lastEventNanos[proxyId] ?: return false
        return last - nanos > 0
    }

    // ─── 조회 ────────────────────────────────────────────────────

    /**
     * 접속자 목록. 같은 UUID 가 여러 프록시에 잡혀 있으면 한 명으로 센다
     * (이 프록시 쪽을 우선하고, 아니면 가장 최근에 들어온 쪽).
     */
    fun all(includeVanished: Boolean, localOnly: Boolean = false): List<GlobalPlayer> {
        val result = LinkedHashMap<UUID, GlobalPlayer>(local.size * 2 + 16)
        for (player in local.values) {
            result[player.uuid] = player
        }
        if (!localOnly) {
            for (players in remote.values) {
                for (player in players.values) {
                    val existing = result[player.uuid]
                    if (existing == null || (existing.proxyId != selfId && player.joinedAt > existing.joinedAt)) {
                        result[player.uuid] = player
                    }
                }
            }
        }
        return if (includeVanished) result.values.toList() else result.values.filter { !it.vanished }
    }

    fun count(includeVanished: Boolean = false, localOnly: Boolean = false): Int = all(includeVanished, localOnly).size

    fun find(uuid: UUID): GlobalPlayer? = local[uuid] ?: all(includeVanished = true).firstOrNull { it.uuid == uuid }

    fun find(name: String): GlobalPlayer? = all(includeVanished = true).firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun proxies(selfMeta: ProxyMeta): List<ProxyInfo> {
        val list = ArrayList<ProxyInfo>()
        list += ProxyInfo(selfId, local.size, selfMeta.startedAt, selfMeta.lastHeartbeat, selfMeta.version, self = true)
        for ((id, meta) in remoteMeta) {
            list += ProxyInfo(id, remote[id]?.size ?: 0, meta.startedAt, meta.lastHeartbeat, meta.version, self = false)
        }
        return list.sortedWith(compareBy({ !it.self }, { it.id }))
    }

    fun remoteProxyIds(): Set<String> = remoteMeta.keys.toSet()
}
