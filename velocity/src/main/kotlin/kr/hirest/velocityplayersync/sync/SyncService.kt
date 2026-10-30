package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.config.DuplicateLoginAction
import kr.hirest.velocityplayersync.config.PluginConfig
import kr.hirest.velocityplayersync.model.GlobalPlayer
import kr.hirest.velocityplayersync.model.ProxyInfo
import org.slf4j.Logger
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.Pipeline
import redis.clients.jedis.params.SetParams
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

sealed interface LoginCheck {
    data object Allowed : LoginCheck

    /** [messageKey] 는 config.yml messages 의 키 */
    data class Denied(val messageKey: String) : LoginCheck
}

/**
 * 이 프록시의 접속자를 Redis 에 기록하고, 다른 프록시의 접속자를 [registry] 로 받아온다.
 * Velocity API 에 의존하지 않는다. Velocity 이벤트는 리스너가 이 클래스의 on* 메서드로 넘겨준다.
 *
 * 스레드
 * - VPS-Writer     : 이 프록시 상태 쓰기 전부(하트비트, 입장/퇴장 반영). 한 스레드라 순서가 보장된다.
 * - VPS-Reader     : 다른 프록시 목록 전체 동기화
 * - VPS-Subscriber : Pub/Sub 수신. 끊기면 다시 연결한다.
 */
class SyncService(
    private val config: PluginConfig,
    val registry: PlayerRegistry,
    private val redis: RedisConnector,
    private val logger: Logger,
    private val pluginVersion: String,
    /** 다른 프록시가 중복 접속 때문에 이 프록시의 플레이어를 끊어 달라고 요청했을 때 */
    private val kickHandler: (UUID) -> Unit,
) {
    val proxyId: String = config.proxyId
    val instanceId: String = UUID.randomUUID().toString()

    private val keys = RedisKeys(config.redis.keyPrefix)
    private val heartbeatMs = config.sync.heartbeatInterval.toMillis()
    private val timeoutMs = config.sync.proxyTimeout.toMillis()
    private val resyncMs = config.sync.fullResyncInterval.toMillis()

    /** 같은 proxy-id 의 다른 인스턴스가 이 시간 안에 하트비트를 남겼으면 살아 있는 것으로 본다. */
    private val conflictThresholdMs = heartbeatMs * 2 + 1000
    private val startedAt = System.currentTimeMillis()

    /** 전체 동기화 간격이 proxy-timeout 보다 길어도 동기화 사이마다 비정상으로 보이지 않게 한다. */
    private val healthyWindowMs = maxOf(timeoutMs, resyncMs + heartbeatMs)

    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("VPS-Writer"))
    private val reader: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("VPS-Reader"))
    private var subscriberThread: Thread? = null

    @Volatile private var subscriberConnection: Jedis? = null
    @Volatile private var pubSub: JedisPubSub? = null

    @Volatile private var running = false

    /** 종료 정리까지 끝나 Redis 에 더는 쓰지 않는 상태. [running] 이 false 여도 이전까지는 쌓인 쓰기를 마저 처리한다. */
    @Volatile private var closed = false

    /** 이 프록시 상태를 Redis 에 통째로 다시 써야 하는지 (시작 직후, 쓰기 실패 후) */
    @Volatile private var dirty = true
    @Volatile private var lastResyncOkAt = 0L
    @Volatile private var lastSelfHeartbeat = 0L

    /**
     * 마지막으로 Redis 문제(연결 실패, 데이터 유실, 구독 끊김)를 겪은 시각.
     * 그 직후에는 다른 프록시가 사라져 보여도 Redis 쪽 문제일 수 있어 TAB 유령 정리를 하지 않는다.
     */
    @Volatile private var lastRedisTroubleAt = 0L

    /** Redis 서버 시계 - 이 프록시 시계 (밀리초) */
    @Volatile private var redisClockOffset = 0L

    /** 같은 proxy-id 를 쓰는 다른 프록시가 살아 있어서 이 프록시가 쓰기를 멈춘 상태 */
    @Volatile var inConflict = false
        private set
    private var conflictSince = 0L
    private var conflictEscalated = false

    private data class PendingLogin(val vanished: Boolean, val lockValue: String?, val createdAt: Long)

    private val pendingLogins = ConcurrentHashMap<UUID, PendingLogin>()
    private val failureLog = FailureLog(logger)

    // ─── 생명주기 ────────────────────────────────────────────────

    fun start() {
        running = true
        startSubscriber()
        writer.scheduleWithFixedDelay(::heartbeat, 0, heartbeatMs, TimeUnit.MILLISECONDS)
        reader.scheduleWithFixedDelay(::resync, 0, resyncMs, TimeUnit.MILLISECONDS)
    }

    /**
     * @param graceful false 면 Redis 에서 자기 상태를 지우지 않는다(비정상 종료와 같다. 테스트용).
     * @param proxyStopping 프록시 자체가 꺼지는 중인지. 그렇다면 아직 남은 접속자의 퇴장을 TAB 에도 알린다.
     *   `/vps reload` 처럼 플레이어가 그대로 접속해 있는 경우에는 false 여야 한다.
     */
    fun shutdown(graceful: Boolean = true, proxyStopping: Boolean = false) {
        if (!running) return
        running = false

        runCatching { pubSub?.unsubscribe() }
        runCatching { subscriberConnection?.close() }
        reader.shutdownNow()

        if (graceful) {
            // 한 스레드라 이미 쌓인 입장·퇴장 쓰기가 먼저 끝난 뒤에 실행된다.
            val cleanup = writer.submit {
                if (inConflict) return@submit
                runCatching {
                    redis.use { j ->
                        val t = j.multi()
                        t.del(keys.players(proxyId))
                        t.del(keys.proxy(proxyId))
                        t.srem(keys.proxies, proxyId)
                        t.publish(keys.events, SyncCodec.encode(message(MessageType.PROXY_DOWN)))
                        // TAB 은 종료할 때 퇴장 알림 일부를 보내지 못하고 꺼지는 경우가 있다(실제 확인). 대신 알린다.
                        if (proxyStopping && config.tabGhostCleanup.enabled) {
                            registry.localPlayers().forEach { publishTabQuit(t::publish, it.uuid) }
                        }
                        t.exec()
                    }
                }.onFailure { logger.warn("종료 중 Redis 정리에 실패했습니다: {}", it.message) }
            }
            runCatching { cleanup.get(timeoutMs, TimeUnit.MILLISECONDS) }
        }
        closed = true
        writer.shutdownNow()
        runCatching { writer.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { subscriberThread?.join(2000) }
    }

    /** 마지막 전체 동기화가 [healthyWindowMs] 안에 성공했는지 */
    fun isHealthy(): Boolean = running && System.currentTimeMillis() - lastResyncOkAt < healthyWindowMs

    fun proxies(): List<ProxyInfo> =
        registry.proxies(PlayerRegistry.ProxyMeta(startedAt, lastSelfHeartbeat, pluginVersion))

    /** 지금의 Redis 서버 시각 추정값. 하트비트 시각과 비교할 때 쓴다. */
    fun estimatedRedisNow(): Long = System.currentTimeMillis() + redisClockOffset

    /** Redis 왕복 시간(밀리초). 실패하면 null. 호출한 스레드를 막는다. */
    fun measureLatencyMs(): Long? = runCatching {
        val start = System.nanoTime()
        redis.use { it.ping() }
        (System.nanoTime() - start) / 1_000_000
    }.getOrNull()

    // ─── Velocity 이벤트 → Redis ─────────────────────────────────

    /**
     * 로그인 직전(LoginEvent)에 호출한다. Redis 를 기다리므로 비동기 스레드에서만 부른다.
     * Redis 에 문제가 있으면 접속을 막지 않는다.
     */
    fun checkLogin(uuid: UUID): LoginCheck {
        if (!running) return LoginCheck.Allowed
        return try {
            redis.use { j -> checkLogin(j, uuid) }
        } catch (e: Exception) {
            markRedisTrouble()
            failureLog.fail("Redis 오류로 중복 접속 확인을 건너뜁니다", e)
            LoginCheck.Allowed
        }
    }

    private fun checkLogin(j: Jedis, uuid: UUID): LoginCheck {
        val vanishedLast = j.get(keys.vanishLast(uuid)) != null
        pendingLogins[uuid] = PendingLogin(vanishedLast, null, System.currentTimeMillis())

        val dup = config.duplicateLogin
        if (!dup.enabled || inConflict) return LoginCheck.Allowed

        val lockValue = "$proxyId:$instanceId:${UUID.randomUUID()}"
        if (j.set(keys.lock(uuid), lockValue, SetParams().nx().px(LOCK_TTL_MS)) == null) {
            return LoginCheck.Denied("duplicate-login-in-progress")
        }
        pendingLogins[uuid] = PendingLogin(vanishedLast, lockValue, System.currentTimeMillis())

        val holder = findRemoteHolder(j, uuid) ?: return LoginCheck.Allowed
        return when (dup.action) {
            DuplicateLoginAction.DENY_NEW -> {
                releaseLock(j, uuid, lockValue)
                pendingLogins[uuid] = PendingLogin(vanishedLast, null, System.currentTimeMillis())
                LoginCheck.Denied("duplicate-login")
            }

            DuplicateLoginAction.KICK_OLD -> {
                j.publish(keys.events, SyncCodec.encode(message(MessageType.KICK, uuid = uuid, target = holder)))
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KICK_WAIT_MS)
                while (System.nanoTime() < deadline) {
                    Thread.sleep(100)
                    if (findRemoteHolder(j, uuid) == null) return LoginCheck.Allowed
                }
                logger.warn("{} 의 기존 접속({})이 {}ms 안에 끊기지 않았지만 새 접속을 허용합니다.", uuid, holder, KICK_WAIT_MS)
                LoginCheck.Allowed
            }
        }
    }

    /** PostLoginEvent */
    fun onJoin(uuid: UUID, name: String, server: String? = null) {
        val pending = pendingLogins.remove(uuid)
        val vanished = pending?.vanished ?: registry.local(uuid)?.vanished ?: false
        registry.putLocal(GlobalPlayer(uuid, name, proxyId, server, System.currentTimeMillis(), vanished))
        submitPlayerSync(uuid, MessageType.JOIN) { p ->
            // 등록을 마친 뒤에 잠금을 풀어야 다른 프록시가 이 접속을 확실히 본다.
            pending?.lockValue?.let { releaseLock(p, uuid, it) }
        }
    }

    /** ServerConnectedEvent */
    fun onServerSwitch(uuid: UUID, server: String) {
        registry.updateLocal(uuid) { it.copy(server = server) } ?: return
        submitPlayerSync(uuid, MessageType.SWITCH)
    }

    /** DisconnectEvent */
    fun onQuit(uuid: UUID) {
        val pending = pendingLogins.remove(uuid)
        val removed = registry.removeLocal(uuid)
        if (removed == null && pending?.lockValue == null) return
        submitPlayerSync(uuid, MessageType.QUIT) { p ->
            pending?.lockValue?.let { releaseLock(p, uuid, it) }
        }
    }

    /** 백엔드의 VPS-Bridge 가 버니시 상태를 알려줬을 때 */
    fun setVanished(uuid: UUID, vanished: Boolean) {
        registry.updateLocal(uuid) { if (it.vanished == vanished) null else it.copy(vanished = vanished) } ?: return
        submitPlayerSync(uuid, MessageType.VANISH) { p ->
            if (vanished) p.set(keys.vanishLast(uuid), "1", SetParams().ex(VANISH_LAST_TTL_SECONDS))
            else p.del(keys.vanishLast(uuid))
        }
    }

    /**
     * [uuid] 의 현재 상태(없으면 퇴장)를 Redis 에 반영하고 알린다.
     * 쓰는 시점의 상태를 읽으므로 이벤트 순서가 뒤섞여도 마지막 상태가 남는다.
     */
    private fun submitPlayerSync(uuid: UUID, type: MessageType, extra: ((Pipeline) -> Unit)? = null) {
        // 종료 중(running=false)에도 정리 전까지는 받는다. 프록시가 꺼질 때 나가는 플레이어의 퇴장을 놓치지 않기 위해서다.
        if (closed) return
        runCatching {
            writer.execute {
                if (closed || inConflict) return@execute
                try {
                    redis.use { j ->
                        val playersKey = keys.players(proxyId)
                        val current = registry.local(uuid)
                        val p = j.pipelined()
                        if (current != null) {
                            p.hset(playersKey, uuid.toString(), SyncCodec.encodePlayer(current))
                            p.pexpire(playersKey, timeoutMs)
                            val sent = if (type == MessageType.QUIT) MessageType.JOIN else type
                            p.publish(keys.events, SyncCodec.encode(message(sent, player = current)))
                        } else {
                            p.hdel(playersKey, uuid.toString())
                            p.publish(keys.events, SyncCodec.encode(message(MessageType.QUIT, uuid = uuid)))
                            // TAB 자체 퇴장 알림이 빠지는 경우를 대비해 이 프록시가 한 번 더 알린다(중복은 TAB 이 무시).
                            if (config.tabGhostCleanup.enabled) publishTabQuit(p::publish, uuid)
                        }
                        extra?.invoke(p)
                        p.sync()
                    }
                } catch (e: Exception) {
                    dirty = true
                    markRedisTrouble()
                    failureLog.fail("Redis 에 접속자 변경을 기록하지 못했습니다", e)
                }
            }
        }
    }

    // ─── 하트비트 (VPS-Writer) ───────────────────────────────────

    private fun heartbeat() {
        if (!running) return
        try {
            redis.use { j ->
                val now = redisTimeMillis(j)
                redisClockOffset = now - System.currentTimeMillis()
                val proxyKey = keys.proxy(proxyId)
                val (owner, ownerHeartbeat) = j.hmget(proxyKey, "instance", "heartbeat")
                // 살아 있는 동안 자기 키가 사라졌다 = Redis 재시작·데이터 유실(또는 긴 멈춤)
                if (owner == null && lastSelfHeartbeat != 0L) markRedisTrouble()

                if (owner != null && owner != instanceId) {
                    val age = now - (ownerHeartbeat?.toLongOrNull() ?: 0L)
                    if (age < conflictThresholdMs) {
                        reportConflict()
                        return@use
                    }
                }
                if (inConflict) {
                    logger.info("proxy-id '{}' 충돌이 해결되어 접속자 공유를 다시 시작합니다.", proxyId)
                    inConflict = false
                    conflictEscalated = false
                }

                if (dirty || owner != instanceId) writeFullState(j, now) else touch(j, now)
                lastSelfHeartbeat = now
                cleanupPendingLogins()
            }
            failureLog.recovered("Redis 연결이 복구되었습니다.")
        } catch (e: Exception) {
            dirty = true
            markRedisTrouble()
            failureLog.fail("Redis 하트비트에 실패했습니다", e)
        }
    }

    /**
     * 비정상 종료 직후 다시 켜면 이전 인스턴스의 하트비트가 몇 초 동안 남아 있어 잠깐 충돌로 보인다.
     * 그래서 처음에는 경고만 하고, 그 시간이 지나도 계속되면(= 실제로 proxy-id 가 겹침) 오류로 알린다.
     */
    private fun reportConflict() {
        val now = System.currentTimeMillis()
        if (!inConflict) {
            inConflict = true
            conflictSince = now
            logger.warn(
                "같은 proxy-id '{}' 의 다른 인스턴스가 살아 있어 잠시 접속자 공유를 멈춥니다. " +
                    "직전에 비정상 종료된 뒤 다시 켠 것이라면 몇 초 안에 저절로 해결됩니다.",
                proxyId,
            )
        } else if (!conflictEscalated && now - conflictSince > conflictThresholdMs * 2) {
            conflictEscalated = true
            logger.error(
                "다른 프록시가 같은 proxy-id '{}' 를 쓰고 있어 이 프록시의 접속자가 공유되지 않습니다. " +
                    "config.yml 의 proxy-id 를 프록시마다 다르게 설정하세요.",
                proxyId,
            )
        }
    }

    private fun writeFullState(j: Jedis, now: Long) {
        val playersKey = keys.players(proxyId)
        val proxyKey = keys.proxy(proxyId)
        val players = registry.localPlayers()
        val t = j.multi()
        t.del(playersKey)
        if (players.isNotEmpty()) {
            t.hset(playersKey, players.associate { it.uuid.toString() to SyncCodec.encodePlayer(it) })
            t.pexpire(playersKey, timeoutMs)
        }
        t.hset(
            proxyKey,
            mapOf(
                "instance" to instanceId,
                "startedAt" to startedAt.toString(),
                "version" to pluginVersion,
                "heartbeat" to now.toString(),
            ),
        )
        t.pexpire(proxyKey, timeoutMs)
        t.sadd(keys.proxies, proxyId)
        t.publish(keys.events, SyncCodec.encode(message(MessageType.PROXY_UP)))
        t.exec()
        dirty = false
    }

    private fun touch(j: Jedis, now: Long) {
        val proxyKey = keys.proxy(proxyId)
        val p = j.pipelined()
        p.hset(proxyKey, "heartbeat", now.toString())
        p.pexpire(proxyKey, timeoutMs)
        p.pexpire(keys.players(proxyId), timeoutMs)
        p.sadd(keys.proxies, proxyId)
        p.sync()
    }

    private fun cleanupPendingLogins() {
        val limit = System.currentTimeMillis() - PENDING_LOGIN_TTL_MS
        pendingLogins.entries.removeIf { it.value.createdAt < limit }
    }

    // ─── 전체 동기화 (VPS-Reader) ────────────────────────────────

    private fun resync() {
        if (!running) return
        val readStarted = System.nanoTime()
        try {
            redis.use { j ->
                val others = j.smembers(keys.proxies).filter { it != proxyId }
                val p = j.pipelined()
                val reads = others.map { id -> Triple(id, p.hgetAll(keys.proxy(id)), p.hgetAll(keys.players(id))) }
                val selfOwner = p.hget(keys.proxy(proxyId), "instance")
                p.sync()
                // 하트비트를 쓴 적이 있는데 자기 키가 없다 = Redis 데이터 유실(FLUSHALL, 빈 상태로 재시작 등).
                // 하트비트보다 이 읽기가 먼저 돌 수 있으므로 여기서도 확인한다.
                if (selfOwner.get() == null && lastSelfHeartbeat != 0L) markRedisTrouble()

                val alive = HashSet<String>()
                val dead = ArrayList<String>()
                val silentlyRemoved = ArrayList<GlobalPlayer>()
                for ((id, metaResponse, playersResponse) in reads) {
                    val meta = metaResponse.get()
                    if (meta.isEmpty()) {
                        dead += id
                        continue
                    }
                    alive += id
                    val players = playersResponse.get().mapNotNull { (uuid, json) -> SyncCodec.decodePlayer(id, uuid, json) }
                    val proxyMeta = PlayerRegistry.ProxyMeta(
                        startedAt = meta["startedAt"]?.toLongOrNull() ?: 0L,
                        lastHeartbeat = meta["heartbeat"]?.toLongOrNull() ?: 0L,
                        version = meta["version"] ?: "?",
                    )
                    registry.replaceProxy(id, players, proxyMeta, readStarted)?.let(silentlyRemoved::addAll)
                }
                silentlyRemoved += registry.retainProxies(alive, readStarted)
                if (dead.isNotEmpty()) j.srem(keys.proxies, *dead.toTypedArray())
                if (silentlyRemoved.isNotEmpty() && config.tabGhostCleanup.enabled) cleanTabGhosts(j, silentlyRemoved)
            }
            lastResyncOkAt = System.currentTimeMillis()
        } catch (e: Exception) {
            markRedisTrouble()
            failureLog.fail("Redis 에서 접속자 목록을 읽지 못했습니다", e)
        }
    }

    /**
     * 퇴장 알림 없이 사라진 플레이어(= 프록시 비정상 종료 등)를 TAB 에서도 지운다. [TabGhostCleaner] 참고.
     * 여러 프록시가 동시에 알아채도 플레이어마다 한 프록시만 보내도록 잠금을 건다.
     */
    private fun cleanTabGhosts(j: Jedis, removed: List<GlobalPlayer>) {
        // Redis 가 재시작됐거나 이 프록시의 연결이 불안정했다면, 다른 프록시가 사라져 보이는 건
        // 그 프록시가 아직 자기 정보를 다시 쓰지 못했기 때문일 수 있다. 살아 있는 플레이어를 지우면 안 되므로 건너뛴다.
        if (System.currentTimeMillis() - lastRedisTroubleAt < timeoutMs * 2) {
            logger.info("최근 Redis 연결이 불안정해, 사라진 플레이어 {}명의 TAB 정리는 건너뜁니다.", removed.size)
            return
        }
        // 그새 다른 프록시로 다시 들어온 플레이어는 건드리지 않는다.
        val targets = removed.map { it.uuid }.distinct().filter { registry.find(it) == null }
        if (targets.isEmpty()) return

        val locking = j.pipelined()
        val locks = targets.map { it to locking.set(keys.tabQuit(it), proxyId, SetParams().nx().px(TAB_QUIT_LOCK_MS)) }
        locking.sync()
        val mine = locks.filter { it.second.get() != null }.map { it.first }
        if (mine.isEmpty()) return

        val publishing = j.pipelined()
        mine.forEach { publishTabQuit(publishing::publish, it) }
        publishing.sync()
        logger.info("퇴장 알림 없이 사라진 플레이어 {}명을 TAB 탭리스트에서 정리했습니다.", mine.size)
    }

    private fun publishTabQuit(publish: (String, String) -> Any, uuid: UUID) {
        publish(config.tabGhostCleanup.channel, TabGhostCleaner.playerQuit(uuid))
    }

    private fun markRedisTrouble() {
        lastRedisTroubleAt = System.currentTimeMillis()
    }

    private fun scheduleResync(delayMs: Long) {
        if (!running) return
        runCatching { reader.schedule(::resync, delayMs, TimeUnit.MILLISECONDS) }
    }

    // ─── Pub/Sub (VPS-Subscriber) ────────────────────────────────

    private fun startSubscriber() {
        subscriberThread = Thread({
            var backoffMs = 1000L
            while (running) {
                try {
                    redis.openSubscriber().use { connection ->
                        subscriberConnection = connection
                        val listener = object : JedisPubSub() {
                            override fun onSubscribe(channel: String, subscribedChannels: Int) {
                                backoffMs = 1000L
                                // 끊겨 있던 동안 놓친 메시지를 보충한다.
                                scheduleResync(0)
                            }

                            override fun onMessage(channel: String, message: String) {
                                handleMessage(message)
                            }
                        }
                        pubSub = listener
                        if (running) connection.subscribe(listener, keys.events)
                    }
                } catch (e: Exception) {
                    if (!running) break
                    markRedisTrouble()
                    failureLog.fail("Redis 구독이 끊겼습니다. ${backoffMs / 1000}초 뒤 다시 연결합니다", e)
                    runCatching { Thread.sleep(backoffMs) }
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                } finally {
                    subscriberConnection = null
                    pubSub = null
                }
            }
        }, "VPS-Subscriber").apply {
            isDaemon = true
            start()
        }
    }

    internal fun handleMessage(raw: String) {
        val msg = SyncCodec.decode(raw) ?: return
        if (msg.proxyId == proxyId) return

        when (msg.type) {
            MessageType.JOIN, MessageType.SWITCH, MessageType.VANISH -> msg.player?.let(registry::upsertRemote)
            MessageType.QUIT -> msg.uuid?.let { registry.removeRemote(msg.proxyId, it) }
            MessageType.PROXY_UP -> scheduleResync(RESYNC_AFTER_PROXY_UP_MS)
            MessageType.PROXY_DOWN -> registry.removeProxy(msg.proxyId)
            MessageType.KICK -> {
                val uuid = msg.uuid ?: return
                if (msg.target == proxyId && registry.local(uuid) != null) {
                    runCatching { kickHandler(uuid) }.onFailure { logger.warn("중복 접속 플레이어를 끊지 못했습니다: {}", it.message) }
                }
            }
        }
    }

    // ─── 도우미 ──────────────────────────────────────────────────

    private fun findRemoteHolder(j: Jedis, uuid: UUID): String? {
        val others = j.smembers(keys.proxies).filter { it != proxyId }
        if (others.isEmpty()) return null
        val p = j.pipelined()
        val checks = others.map { it to p.hexists(keys.players(it), uuid.toString()) }
        p.sync()
        return checks.firstOrNull { it.second.get() }?.first
    }

    private fun releaseLock(j: Jedis, uuid: UUID, value: String) {
        j.eval(RELEASE_LOCK_SCRIPT, listOf(keys.lock(uuid)), listOf(value))
    }

    private fun releaseLock(p: Pipeline, uuid: UUID, value: String) {
        p.eval(RELEASE_LOCK_SCRIPT, listOf(keys.lock(uuid)), listOf(value))
    }

    /** 프록시마다 시계가 다를 수 있으므로 하트비트 시각은 Redis 서버 시간으로 기록한다. */
    private fun redisTimeMillis(j: Jedis): Long {
        val (seconds, micros) = j.time()
        return seconds.toLong() * 1000 + micros.toLong() / 1000
    }

    private fun message(type: MessageType, player: GlobalPlayer? = null, uuid: UUID? = null, target: String? = null) =
        SyncMessage(type, proxyId, instanceId, player, uuid, target)

    private fun daemon(name: String) = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    companion object {
        const val LOCK_TTL_MS = 5_000L
        const val KICK_WAIT_MS = 3_000L
        const val PENDING_LOGIN_TTL_MS = 60_000L
        const val RESYNC_AFTER_PROXY_UP_MS = 500L
        const val VANISH_LAST_TTL_SECONDS = 30L * 24 * 60 * 60
        const val TAB_QUIT_LOCK_MS = 60_000L

        /** 내가 건 잠금일 때만 푼다. */
        private const val RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}

/** 같은 실패 로그가 계속 쌓이지 않도록 1분에 한 번만 남기고, 복구되면 알린다. */
private class FailureLog(private val logger: Logger, private val intervalMs: Long = 60_000L) {
    @Volatile private var failing = false
    @Volatile private var lastLoggedAt = 0L

    fun fail(message: String, error: Throwable) {
        val now = System.currentTimeMillis()
        if (!failing || now - lastLoggedAt >= intervalMs) {
            logger.warn("{}: {}", message, error.message ?: error.javaClass.simpleName)
            lastLoggedAt = now
        }
        failing = true
    }

    fun recovered(message: String) {
        if (failing) {
            failing = false
            logger.info(message)
        }
    }
}
