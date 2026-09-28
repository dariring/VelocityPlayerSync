package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.player
import kr.hirest.velocityplayersync.uuidOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncCodecTest {

    @Test
    fun `player JSON round trip keeps every field`() {
        val original = player("Steve", "proxy-a", server = "lobby", vanished = true, joinedAt = 12345L)
        val json = SyncCodec.encodePlayer(original)
        assertEquals(original, SyncCodec.decodePlayer("proxy-a", original.uuid.toString(), json))
    }

    @Test
    fun `player without server decodes to null server`() {
        val original = player("Alex", "proxy-b")
        assertEquals(original, SyncCodec.decodePlayer("proxy-b", original.uuid.toString(), SyncCodec.encodePlayer(original)))
    }

    @Test
    fun `message round trip`() {
        val join = SyncMessage(MessageType.JOIN, "proxy-a", "inst", player = player("Steve", "proxy-a", "lobby"))
        assertEquals(join.copy(uuid = join.player!!.uuid), SyncCodec.decode(SyncCodec.encode(join)))

        val kick = SyncMessage(MessageType.KICK, "proxy-b", "inst2", uuid = uuidOf("Steve"), target = "proxy-a")
        assertEquals(kick, SyncCodec.decode(SyncCodec.encode(kick)))
    }

    @Test
    fun `broken or unknown messages are ignored`() {
        assertNull(SyncCodec.decode("not json"))
        assertNull(SyncCodec.decode("""{"v":99,"type":"JOIN","proxy":"a","instance":"i"}"""))
        assertNull(SyncCodec.decode("""{"v":1,"type":"UNKNOWN","proxy":"a","instance":"i"}"""))
        assertNull(SyncCodec.decodePlayer("a", "not-a-uuid", "{}"))
    }
}
