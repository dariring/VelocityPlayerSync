package kr.hirest.velocityplayersync.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * TAB 의 Redis 프록시 동기화(proxy-support: REDIS)는 프록시가 정상 종료될 때만 퇴장을 알린다.
 * 프록시가 비정상 종료되면 그 프록시의 플레이어가 다른 프록시의 TAB 탭리스트와 %online% 에 계속 남는다.
 *
 * VelocityPlayerSync 는 하트비트로 죽은 프록시를 알아내므로, 그 플레이어들에 대해
 * TAB 과 같은 형식의 PlayerQuit 메시지를 TAB 채널에 대신 보내 유령을 지운다.
 *
 * 형식 (TAB 6.x, delivery4j RedisBroker 로 확인):
 *   PUBLISH <channel> base64( int 메시지ID, int 줄수=1, UTF base64( UTF 보낸프록시UUID, UTF "PlayerQuit", long, long ) )
 * TAB 은 모르는 플레이어의 퇴장을 디버그 로그로만 남기므로 중복으로 보내도 문제없다.
 */
object TabGhostCleaner {

    /** TAB 은 자기가 보낸 메시지만 무시한다. 어떤 TAB 인스턴스와도 겹치지 않는 값이면 된다. */
    private val senderId = UUID.randomUUID().toString()

    fun playerQuit(uuid: UUID): String {
        val inner = bytes { out ->
            out.writeUTF(senderId)
            out.writeUTF("PlayerQuit")
            out.writeLong(uuid.mostSignificantBits)
            out.writeLong(uuid.leastSignificantBits)
        }
        val outer = bytes { out ->
            // delivery4j 가 최근 ID 를 기억해 중복을 거르므로 매번 다른 값을 쓴다.
            out.writeInt(ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE))
            out.writeInt(1)
            out.writeUTF(Base64.getEncoder().encodeToString(inner))
        }
        return Base64.getEncoder().encodeToString(outer)
    }

    private inline fun bytes(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(write)
        return buffer.toByteArray()
    }
}
