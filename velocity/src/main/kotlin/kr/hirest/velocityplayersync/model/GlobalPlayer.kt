package kr.hirest.velocityplayersync.model

import java.util.UUID

/** 네트워크 어딘가(이 프록시 또는 다른 프록시)에 접속 중인 플레이어. */
data class GlobalPlayer(
    val uuid: UUID,
    val name: String,
    val proxyId: String,
    /** 접속 중인 백엔드 서버. 로그인 직후 아직 서버에 붙기 전이면 null. */
    val server: String?,
    val joinedAt: Long,
    val vanished: Boolean,
)

/** Redis 에 등록된 프록시 한 대의 상태. */
data class ProxyInfo(
    val id: String,
    val playerCount: Int,
    val startedAt: Long,
    /** 마지막 하트비트 시각(Redis 서버 시간, 밀리초). 알 수 없으면 0. */
    val lastHeartbeat: Long,
    val version: String,
    val self: Boolean,
)
