package kr.hirest.velocityplayersync.sync

import java.util.UUID

/** Redis 키·채널 이름. 구조는 PLAN.md 5.1 참고. */
class RedisKeys(private val prefix: String) {
    /** 등록된 proxyId 목록 (SET) */
    val proxies = "$prefix:proxies"

    /** Pub/Sub 채널 */
    val events = "$prefix:events"

    /** 프록시 상태 (HASH: instance, startedAt, version, heartbeat) */
    fun proxy(proxyId: String) = "$prefix:proxy:$proxyId"

    /** 프록시의 접속자 (HASH: uuid → JSON) */
    fun players(proxyId: String) = "$prefix:players:$proxyId"

    /** 중복 접속 판정용 잠금 (STRING) */
    fun lock(uuid: UUID) = "$prefix:lock:$uuid"

    /** TAB 유령 정리를 한 프록시만 하도록 거는 잠금 (STRING) */
    fun tabQuit(uuid: UUID) = "$prefix:tab-quit:$uuid"

    /** 마지막 버니시 상태 (STRING, 버니시일 때만 존재) */
    fun vanishLast(uuid: UUID) = "$prefix:vanish-last:$uuid"
}
