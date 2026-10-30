package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.config.RedisSettings
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RedisConnectorTest {

    @Test
    fun `an unparsable url does not put the password in the error message`() {
        // 인코딩하지 않은 특수문자: 첫 번째는 URI 문법 오류, 두 번째는 호스트를 찾지 못하는 경우
        for (url in listOf("redis://:se cret@localhost:6379/0", "redis://:se@cret@localhost:6379/0")) {
            val error = assertFailsWith<IllegalArgumentException>(url) { RedisConnector(RedisSettings(url, "vps", 100)) }
            assertFalse(error.message.orEmpty().contains("cret"), "비밀번호가 메시지에 들어 있습니다: ${error.message}")
        }
    }
}
