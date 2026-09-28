package kr.hirest.velocityplayersync

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

/** config.yml 의 messages 를 MiniMessage 로 바꾼다. */
class Messages(private val raw: Map<String, String>) {

    private val mm = MiniMessage.miniMessage()

    /**
     * @param values {키} 자리에 들어갈 값. 플레이어 이름 같은 외부 값이므로 태그로 해석되지 않게 이스케이프한다.
     * @param rawValues 이미 MiniMessage 형식인 값 (다른 메시지를 조합한 것 등)
     */
    fun get(key: String, values: Map<String, Any> = emptyMap(), rawValues: Map<String, String> = emptyMap()): Component =
        mm.deserialize(format(key, values, rawValues))

    fun format(key: String, values: Map<String, Any> = emptyMap(), rawValues: Map<String, String> = emptyMap()): String {
        var text = raw[key] ?: return "<red>[VPS] 메시지 '$key' 가 config.yml 에 없습니다.</red>"
        text = text.replace("{prefix}", raw["prefix"] ?: "")
        for ((k, v) in rawValues) text = text.replace("{$k}", v)
        for ((k, v) in values) text = text.replace("{$k}", escape(v.toString()))
        return text
    }

    fun escape(value: String): String = mm.escapeTags(value)
}
