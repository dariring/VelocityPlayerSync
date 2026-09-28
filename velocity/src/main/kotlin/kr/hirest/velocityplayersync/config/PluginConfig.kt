package kr.hirest.velocityplayersync.config

import java.time.Duration

data class PluginConfig(
    val proxyId: String,
    val redis: RedisSettings,
    val sync: SyncSettings,
    val ping: PingSettings,
    val duplicateLogin: DuplicateLoginSettings,
    val tabGhostCleanup: TabGhostCleanupSettings,
    val messages: Map<String, String>,
)

data class RedisSettings(
    val url: String,
    val keyPrefix: String,
    val timeoutMs: Int,
)

data class SyncSettings(
    val heartbeatInterval: Duration,
    val proxyTimeout: Duration,
    val fullResyncInterval: Duration,
)

enum class MaxPlayersMode { KEEP, FIXED, ONLINE_PLUS }

enum class RedisFailureMode { LOCAL_ONLY, LAST_KNOWN }

data class PingSettings(
    val enabled: Boolean,
    val maxPlayersMode: MaxPlayersMode,
    val fixedMaxPlayers: Int,
    val onlinePlus: Int,
    val sampleEnabled: Boolean,
    val sampleMaxEntries: Int,
    val onRedisFailure: RedisFailureMode,
)

enum class DuplicateLoginAction { KICK_OLD, DENY_NEW }

data class TabGhostCleanupSettings(
    val enabled: Boolean,
    /** TAB 의 Redis 채널 이름: "TAB_4_" + TAB config 의 proxy-support.channel-name-suffix */
    val channel: String,
)

data class DuplicateLoginSettings(
    val enabled: Boolean,
    val action: DuplicateLoginAction,
)
