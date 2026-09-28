package kr.hirest.velocityplayersync.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import kr.hirest.velocityplayersync.VelocityPlayerSync
import kr.hirest.velocityplayersync.model.GlobalPlayer

/** `/vps status | list [서버] | find <닉네임> | reload` */
class VpsCommand(private val plugin: VelocityPlayerSync) : SimpleCommand {

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission(PERMISSION_ADMIN)

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        val sub = args.firstOrNull()?.lowercase()

        if (sub == "reload") {
            // 리로드는 Redis 종료·재연결을 기다리므로 명령 스레드를 막지 않게 따로 돌린다.
            async {
                val ok = plugin.reload()
                val messages = plugin.runtime?.messages ?: plugin.fallbackMessages
                source.sendMessage(messages.get(if (ok) "reload-success" else "reload-failed"))
            }
            return
        }

        val runtime = plugin.runtime
        if (runtime == null) {
            source.sendMessage(plugin.fallbackMessages.get("disabled"))
            return
        }
        val messages = runtime.messages

        when (sub) {
            "status" -> async { status(source, runtime) }
            "list" -> list(source, runtime, args.getOrNull(1))
            "find" -> {
                val name = args.getOrNull(1)
                if (name == null) source.sendMessage(messages.get("usage")) else find(source, runtime, name)
            }

            else -> source.sendMessage(messages.get("usage"))
        }
    }

    private fun status(source: CommandSource, runtime: VelocityPlayerSync.Runtime) {
        val messages = runtime.messages
        val sync = runtime.sync
        val latency = sync.measureLatencyMs()
        val redisText = if (latency != null) {
            messages.format("status-redis-ok", mapOf("latency" to latency))
        } else {
            messages.format("status-redis-fail")
        }
        val proxies = sync.proxies()
        val online = runtime.registry.count(includeVanished = true)

        source.sendMessage(
            messages.get("status-header", mapOf("proxies" to proxies.size, "online" to online), mapOf("redis" to redisText)),
        )
        val now = sync.estimatedRedisNow()
        for (proxy in proxies) {
            val heartbeat = if (proxy.lastHeartbeat <= 0) "-" else "${((now - proxy.lastHeartbeat) / 1000).coerceAtLeast(0)}초 전"
            source.sendMessage(
                messages.get(
                    "status-line",
                    mapOf("proxy" to proxy.id, "players" to proxy.playerCount, "heartbeat" to heartbeat),
                    mapOf("self" to if (proxy.self) messages.format("status-self") else ""),
                ),
            )
        }
        if (sync.inConflict) {
            source.sendMessage(messages.get("status-conflict", mapOf("proxy" to sync.proxyId)))
        }
    }

    private fun list(source: CommandSource, runtime: VelocityPlayerSync.Runtime, serverFilter: String?) {
        val messages = runtime.messages
        val seeVanished = source.hasPermission(PERMISSION_SEE_VANISHED)
        val noServer = messages.format("no-server")
        val players = runtime.registry.all(includeVanished = seeVanished)
            .filter { serverFilter == null || it.server.equals(serverFilter, ignoreCase = true) }

        if (players.isEmpty()) {
            source.sendMessage(messages.get("list-empty"))
            return
        }

        source.sendMessage(messages.get("list-header", mapOf("count" to players.size)))
        val vanishedTag = messages.format("list-vanished-tag")
        players.groupBy { it.server }
            .toSortedMap(nullsLast(String.CASE_INSENSITIVE_ORDER))
            .forEach { (server, members) ->
                val names = members.sortedBy { it.name.lowercase() }.joinToString("<gray>, </gray>") {
                    (if (it.vanished) vanishedTag else "") + messages.escape(it.name)
                }
                source.sendMessage(
                    messages.get(
                        "list-server",
                        mapOf("count" to members.size),
                        mapOf("server" to (server?.let(messages::escape) ?: noServer), "players" to names),
                    ),
                )
            }
    }

    private fun find(source: CommandSource, runtime: VelocityPlayerSync.Runtime, name: String) {
        val messages = runtime.messages
        val player = runtime.registry.find(name)
            ?.takeUnless { it.vanished && !source.hasPermission(PERMISSION_SEE_VANISHED) }
        if (player == null) {
            source.sendMessage(messages.get("find-not-found", mapOf("player" to name)))
            return
        }
        source.sendMessage(
            messages.get(
                "find-result",
                mapOf("player" to player.name, "proxy" to player.proxyId),
                mapOf("server" to (player.server?.let(messages::escape) ?: messages.format("no-server"))),
            ),
        )
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        val source = invocation.source()
        if (args.size <= 1) {
            val prefix = args.firstOrNull()?.lowercase() ?: ""
            return SUBCOMMANDS.filter { it.startsWith(prefix) }
        }
        if (args.size != 2) return emptyList()

        val prefix = args[1].lowercase()
        val runtime = plugin.runtime ?: return emptyList()
        val candidates: Collection<String> = when (args[0].lowercase()) {
            "list" -> (plugin.server.allServers.map { it.serverInfo.name } +
                runtime.registry.all(includeVanished = true).mapNotNull(GlobalPlayer::server)).distinct()

            "find" -> runtime.registry.all(includeVanished = source.hasPermission(PERMISSION_SEE_VANISHED)).map { it.name }
            else -> emptyList()
        }
        return candidates.filter { it.lowercase().startsWith(prefix) }.sortedBy { it.lowercase() }.take(100)
    }

    private fun async(task: () -> Unit) {
        plugin.server.scheduler.buildTask(plugin, Runnable(task)).schedule()
    }

    companion object {
        const val PERMISSION_ADMIN = "velocityplayersync.admin"
        const val PERMISSION_SEE_VANISHED = "velocityplayersync.see-vanished"
        private val SUBCOMMANDS = listOf("status", "list", "find", "reload")
    }
}
