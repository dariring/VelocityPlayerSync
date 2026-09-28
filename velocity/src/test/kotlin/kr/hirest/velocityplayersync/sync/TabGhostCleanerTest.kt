package kr.hirest.velocityplayersync.sync

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TabGhostCleanerTest {

    /** TAB 6.x 가 받는 순서대로 읽는다 (delivery4j → ProxySupport.processMessage → PlayerQuit). */
    private fun decodeLikeTab(raw: String): Pair<String, UUID> {
        val outer = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw)))
        outer.readInt() // 메시지 ID
        assertEquals(1, outer.readInt(), "줄 수")
        val line = outer.readUTF()

        val inner = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(line)))
        inner.readUTF() // 보낸 프록시
        val action = inner.readUTF()
        return action to UUID(inner.readLong(), inner.readLong())
    }

    @Test
    fun `PlayerQuit message decodes the way TAB reads it`() {
        val uuid = UUID.randomUUID()
        assertEquals("PlayerQuit" to uuid, decodeLikeTab(TabGhostCleaner.playerQuit(uuid)))
    }

    @Test
    fun `matches a message captured from a real TAB 6_2_0`() {
        // 실제 TAB 이 capbot2 퇴장 때 Redis 에 보낸 값
        val captured = "AA2F0gAAAAEAWEFDUmlZekJoWVRVM055MWhOREExTFRSbU16RXRPRFU0TVMxak1qRmxNVFpoWVRneU5tSUFDbEJzWVhsbGNsRjFhWFNkMkpHVGVZSTAyYk5hdUU3dG8wZzg="
        assertEquals("PlayerQuit" to UUID.fromString("9dd89193-7982-34d9-b35a-b84eeda3483c"), decodeLikeTab(captured))
    }

    @Test
    fun `each message gets a new id so TAB does not drop it as a duplicate`() {
        val uuid = UUID.randomUUID()
        val ids = (1..20).map { DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(TabGhostCleaner.playerQuit(uuid)))).readInt() }
        assertNotEquals(1, ids.toSet().size)
    }
}
