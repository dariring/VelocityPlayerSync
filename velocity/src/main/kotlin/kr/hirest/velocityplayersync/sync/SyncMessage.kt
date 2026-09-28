package kr.hirest.velocityplayersync.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.hirest.velocityplayersync.model.GlobalPlayer
import java.util.UUID

enum class MessageType {
    /** 플레이어 입장. [SyncMessage.player] 포함 */
    JOIN,

    /** 백엔드 서버 이동. [SyncMessage.player] 포함 */
    SWITCH,

    /** 버니시 상태 변경. [SyncMessage.player] 포함 */
    VANISH,

    /** 플레이어 퇴장. [SyncMessage.uuid] 포함 */
    QUIT,

    /** 프록시가 자기 상태를 새로 기록함 (시작, 재연결 등) */
    PROXY_UP,

    /** 프록시 정상 종료 */
    PROXY_DOWN,

    /** [SyncMessage.target] 프록시에게 [SyncMessage.uuid] 플레이어를 끊으라고 요청 */
    KICK,
}

data class SyncMessage(
    val type: MessageType,
    val proxyId: String,
    val instance: String,
    val player: GlobalPlayer? = null,
    val uuid: UUID? = null,
    val target: String? = null,
)

/** Redis 에 저장·발행하는 JSON 형식. */
object SyncCodec {
    private const val VERSION = 1

    fun encodePlayer(player: GlobalPlayer): String = playerJson(player).toString()

    fun decodePlayer(proxyId: String, uuid: String, json: String): GlobalPlayer? = try {
        playerFrom(proxyId, UUID.fromString(uuid), JsonParser.parseString(json).asJsonObject)
    } catch (_: Exception) {
        null
    }

    fun encode(message: SyncMessage): String = JsonObject().apply {
        addProperty("v", VERSION)
        addProperty("type", message.type.name)
        addProperty("proxy", message.proxyId)
        addProperty("instance", message.instance)
        (message.uuid ?: message.player?.uuid)?.let { addProperty("uuid", it.toString()) }
        message.player?.let { add("player", playerJson(it)) }
        message.target?.let { addProperty("target", it) }
    }.toString()

    fun decode(raw: String): SyncMessage? = try {
        val json = JsonParser.parseString(raw).asJsonObject
        if (json.get("v")?.asInt != VERSION) {
            null
        } else {
            val proxyId = json.get("proxy").asString
            val uuid = json.get("uuid")?.asString?.let(UUID::fromString)
            SyncMessage(
                type = MessageType.valueOf(json.get("type").asString),
                proxyId = proxyId,
                instance = json.get("instance").asString,
                player = json.getAsJsonObject("player")?.let { p -> uuid?.let { playerFrom(proxyId, it, p) } },
                uuid = uuid,
                target = json.get("target")?.asString,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun playerJson(player: GlobalPlayer) = JsonObject().apply {
        addProperty("name", player.name)
        player.server?.let { addProperty("server", it) }
        addProperty("joinedAt", player.joinedAt)
        addProperty("vanished", player.vanished)
    }

    private fun playerFrom(proxyId: String, uuid: UUID, json: JsonObject) = GlobalPlayer(
        uuid = uuid,
        name = json.get("name").asString,
        proxyId = proxyId,
        server = json.get("server")?.takeUnless { it.isJsonNull }?.asString,
        joinedAt = json.get("joinedAt")?.asLong ?: 0L,
        vanished = json.get("vanished")?.asBoolean ?: false,
    )
}
