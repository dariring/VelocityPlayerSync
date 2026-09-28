package kr.hirest.velocityplayersync.sync

import kr.hirest.velocityplayersync.config.RedisSettings
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisClientConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.util.JedisURIHelper
import java.net.URI
import java.time.Duration

/**
 * Jedis 연결 풀. 만들 때는 연결하지 않고, 처음 쓸 때 연결한다.
 *
 * Jedis 8 은 JedisPool 대신 RedisClient 를 권하지만, RedisClient 에는 하트비트 시각에 쓰는
 * TIME 명령이 없어 JedisPool 을 그대로 쓴다.
 */
@Suppress("DEPRECATION")
class RedisConnector(private val settings: RedisSettings) : AutoCloseable {

    private val uri: URI = URI.create(settings.url).also {
        require(JedisURIHelper.isValid(it)) { "redis.url '${settings.url}' 을(를) 해석할 수 없습니다." }
    }
    private val address: HostAndPort = JedisURIHelper.getHostAndPort(uri)

    private val pool = JedisPool(
        GenericObjectPoolConfig<Jedis>().apply {
            maxTotal = 16
            maxIdle = 8
            minIdle = 0
            testWhileIdle = true
            setMaxWait(Duration.ofMillis(settings.timeoutMs.toLong()))
            // 재배치된 commons-pool2 가 JMX 에 등록되면 리로드할 때 이름이 겹칠 수 있다.
            jmxEnabled = false
        },
        address,
        clientConfig(socketTimeoutMs = settings.timeoutMs),
    )

    fun <T> use(block: (Jedis) -> T): T = pool.resource.use(block)

    /** Pub/Sub 전용 연결. 구독 중에는 응답을 무기한 기다려야 하므로 읽기 타임아웃이 없다. */
    fun openSubscriber(): Jedis = Jedis(address, clientConfig(socketTimeoutMs = 0))

    private fun clientConfig(socketTimeoutMs: Int): JedisClientConfig =
        DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(settings.timeoutMs)
            .socketTimeoutMillis(socketTimeoutMs)
            .user(JedisURIHelper.getUser(uri))
            .password(JedisURIHelper.getPassword(uri))
            .database(if (JedisURIHelper.hasDbIndex(uri)) JedisURIHelper.getDBIndex(uri) else 0)
            .ssl(JedisURIHelper.isRedisSSLScheme(uri))
            .clientName("VelocityPlayerSync")
            // Jedis(JedisPool)는 RESP2 만 쓴다. 자동 협상을 켜 두면 연결할 때마다 경고를 남긴다.
            .autoNegotiateProtocol(false)
            .build()

    override fun close() {
        pool.close()
    }
}
