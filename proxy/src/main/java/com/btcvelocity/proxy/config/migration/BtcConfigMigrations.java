/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.btcvelocity.proxy.config.migration;

import com.btcvelocity.proxy.bridge.BackendCircuitBreaker;
import com.velocitypowered.proxy.config.migration.ConfigurationMigration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BtcConfigMigrations {

  public static List<ConfigurationMigration> createBtcMigrations() {
    return List.of(
        // root
        migration(
            "Whether chat signing should be enforced. If disabled, backend servers MUST disable chat signing.",
            "enforce-chat-signing",
            true
        ),
        migration(
            "Should tell client that proxy doesn't report chat messages? (useful for NoChatReports mod).",
            "prevents-chat-reports",
            false
        ),
        migration(
            "If false, disables processing of header and footer translations for better performance.",
            "translate-header-footer",
            true
        ),
        migration(
            "If true, a message is pasted in console displaying whether a user joined on an unsupported version.\n"
                + "This corresponds with the \"minimum-version\" and \"modern-forwarding-needs-new-client\" values.",
            "log-minimum-version",
            false
        ),
        migration(
            "Modify the minimum version, so the proxy blocks out users on the wrong version, rather than the backend server.\n"
                + "Modern forwarding supports 1.13, at minimum. Set this to 1.13 or above if you are using modern forwarding.",
            "minimum-version",
            "1.7.2"
        ),
        migration(
            "Modify the maximum version, so the proxy blocks out users on the wrong version, rather than the backend server.\n"
                + "Set to UNBOUNDED for no maximum version (default behavior).",
            "maximum-version",
            "UNBOUNDED"
        ),
        migration(
            "If true, disables handling of inbound Forge handshakes.",
            "disable-forge",
            false
        ),
        migration(
            "If enabled (default is false), kick-existing-players will only kick the existing session when\n"
                + "the new connection originates from the same IP address. A duplicate UUID from a different IP\n"
                + "is denied instead of displacing the existing player. This makes kick-existing-players safe for\n"
                + "offline mode by restricting it to reconnect-after-drop scenarios.\n"
                + "It is recommended to set kick-existing-players-check-ip to true when enabling kick-existing-players\n"
                + "in offline mode.",
            "kick-existing-players-check-ip",
            false
        ),
        migration(
            "If false, disables logging for offline player connections.",
            "log-offline-connections",
            true
        ),
        migration(
            "Enables logging of player connections and by default, still displays\n"
                + "player disconnections and initial connections.",
            "log-player-connections",
            true
        ),
        migration(
            "Enables logging of player disconnection and by default, still displays\n"
                + "player connections and initial connections.",
            "log-player-disconnections",
            true
        ),

        // [commands]
        migration("Velocity Command Registration", "commands.server-enabled", true),
        migration(null, "commands.glist-enabled", true),
        migration(null, "commands.send-enabled", true),
        migration("Velocity-CTD Command Registration", "commands.alert-enabled", true),
        migration(null, "commands.alertraw-enabled", true),
        migration(null, "commands.find-enabled", true),
        migration(null, "commands.hub-enabled", true),
        migration(null, "commands.ping-enabled", true),
        migration(null, "commands.plist-enabled", true),
        migration(null, "commands.transfer-enabled", true),
        migration(
            "Whether to use the default \"/server\" output, or whether to override it "
                + "with the \"velocity.command.server.usage\" key.",
            "commands.override-server-command-usage",
            false
        ),

        // [servers]
        migration(
            "Sends you to the first available fallback server, the least populated\n"
                + " fallback server, or the most populated fallback server.\n"
                + " Available options: \"first_available\", \"least_populated\", \"most_populated\"",
            "servers.dynamic-fallbacks-filter",
            "first_available"
        ),
        migration(
            "The list of aliases for the \"/server\" command when the queue system is enabled.",
            "servers.server-aliases",
            List.of("joinqueue", "queue")
        ),

        // [command-aliases]
        migration(
            "What commands should have aliases for simpler execution that\n"
                + " do not already have a more advanced function or implementation.",
            "command-aliases.hub",
            List.of("lobby", "return")
        ),

        // [proxy-command-aliases]
        migration(
            "Proxy command aliases create new commands that execute other commands when invoked.\n"
                + " This is similar to Bukkit's commands.yml functionality.\n"
                + " Adding multiple aliases executes multiple commands.",
            "proxy-command-aliases.examplealias",
            List.of("velocity help")
        ),

        // [advanced]
        migration(
            "Enables the execution of illegal characters in chat and only allows\n"
                + " or denies illegal characters that are executed through the proxy.",
            "advanced.allow-illegal-characters-in-chat",
            false
        ),
        migration(
            "Modifies the server brand that displays in your debug menu.\n"
                + " Supports placeholders: {protocol-min}, {protocol-max}, {protocol}, {backend-brand},\n"
                + " {backend-brand-custom}, {proxy-brand}, {proxy-brand-custom}, {proxy-version},\n"
                + " {proxy-vendor}, {server-connected}.",
            "advanced.server-brand",
            "{backend-brand} ({proxy-brand})"
        ),
        migration(
            "Modifies the brand and server version that displays in the multiplayer menu and status pingers.\n"
                + " Supports placeholders: {protocol-min}, {protocol-max}, {protocol}, {proxy-brand},\n"
                + " {proxy-brand-custom}, {proxy-version}, {proxy-vendor}, {player-count}, {max-players}.",
            "advanced.fallback-version-ping",
            "{proxy-brand} {protocol-min}-{protocol-max}"
        ),
        migration(
            "Instead of \"fallback-version-ping\" exclusively returning when the user is on an unsupported\n"
                + " version, it is returned regardless of their version and can be used to customize\n"
                + " the player count/max line freely.",
            "advanced.always-fallback-ping",
            false
        ),
        migration(
            "Replaces what is returned for both the server brand and fallback version pinger.",
            "advanced.custom-brand-proxy",
            "Velocity"
        ),
        migration(
            "Replaces what is returned as the server brand for the user's client.",
            "advanced.custom-brand-backend",
            "Paper"
        ),
        migration(
            "Seconds the server list ping response is kept, per client protocol version and forced host\n"
                + " (all hosts that match no forced host share one entry).\n"
                + " The time is counted from the build and never extended. 0 or -1 turns the cache off.\n"
                + " Pings no longer reach the backends, the cluster counts or ProxyPingEvent plugins on each\n"
                + " request. A plugin that reads event.getConnection() in ProxyPingEvent (address, rate, ban) sees\n"
                + " only the connection that builds the response: turn the cache off for such a plugin.\n"
                + " 3 to 10 is a good value against ping floods.",
            "advanced.status-cache-ttl",
            0
        ),
        migration(
            "Passive circuit breaker on backends. Nothing is probed: it watches the connections the proxy makes\n"
                + " for its players. When this many different players have failed to connect to one backend within\n"
                + " the window below (at least 2; a success resets the count to zero), routing plugins\n"
                + " that choose a destination avoid that backend for the open delay, then try it again: the next\n"
                + " success clears it, a failure of a new player avoids it again. One player failing over and over\n"
                + " never opens it. A backend that answers and refuses the player (full, whitelist) is not a failure.\n"
                + " Servers are never removed from the proxy. The state is per proxy: it is not shared through Redis,\n"
                + " each proxy learns from the connections it makes itself.\n"
                + " 0 turns it off, and it is off by default: 3 is the value to use once the behaviour has been\n"
                + " validated on the bench. Read at startup.",
            "advanced.backend-circuit-failures",
            BackendCircuitBreaker.DEFAULT_FAILURES
        ),
        migration(
            "Seconds in which the failed connections are counted.",
            "advanced.backend-circuit-window-seconds",
            BackendCircuitBreaker.DEFAULT_WINDOW_SECONDS
        ),
        migration(
            "Seconds a backend is avoided before it is tried again.",
            "advanced.backend-circuit-open-seconds",
            BackendCircuitBreaker.DEFAULT_OPEN_SECONDS
        ),
        migration(
            "What to do with a connection whose handshake host (the name the player typed) is neither in\n"
                + " handshake-allowed-hosts nor a forced host. Scanners arrive with the IP address as the host.\n"
                + " \"off\" checks nothing (default). \"observe\" lets everyone in, counts the unlisted hosts\n"
                + " (btc_proxy_admission_total) and logs each new one once: run it first, read the list, then\n"
                + " switch to \"enforce\", which closes the connection without an answer.\n"
                + " The Geyser listener is never judged. The comparison ignores case, the DNS root dot and the\n"
                + " Forge marker after the host.",
            "advanced.handshake-host-filter",
            "off"
        ),
        migration(
            "Public names of the proxy, on top of the forced hosts (which are always allowed). Add the IP\n"
                + " address too if staff or a health check connect by IP.",
            "advanced.handshake-allowed-hosts",
            List.of()
        ),
        migration(
            "New connections a single source may open per connection-rate-window-ms, status pings included,\n"
                + " counted before the handshake is read (login-ratelimit only sees logins). The source is the\n"
                + " client address after the PROXY protocol; an IPv6 address counts as its /64. Over the limit the\n"
                + " connection is closed without an answer. 0 (default) turns it off.\n"
                + " CAUTION: behind one NAT, or a load balancer WITHOUT the PROXY protocol, all players are ONE\n"
                + " source and a low value locks them out. Leave it off unless the real client address reaches\n"
                + " the proxy; then start high (for example 20 per 10 s).",
            "advanced.connection-rate-limit",
            0
        ),
        migration(
            "Length of that window, in milliseconds.",
            "advanced.connection-rate-window-ms",
            10000
        ),
        migration(
            "Sources never limited (address or CIDR, never a host name; at most /8 for IPv4).",
            "advanced.connection-rate-exempt",
            List.of()
        ),
        migration(
            "A connection that has not sent its handshake after this many milliseconds is closed (slow-loris\n"
                + " guard), long before read-timeout. 5000 is generous: a handshake is one small packet.\n"
                + " 0 (default) means no deadline.",
            "advanced.handshake-timeout-ms",
            0
        ),

        // [observability]
        migration(
            "Watches the Netty event loops: a loop that has not run a task for watchdog-threshold-ms is logged\n"
                + " as a warning with its thread name and stack (at most once a minute per loop), and its lag is\n"
                + " exported as a metric.",
            "observability.watchdog-enabled",
            true
        ),
        migration(
            "Event loop lag, in milliseconds, above which a loop is reported as blocked.",
            "observability.watchdog-threshold-ms",
            100
        ),
        migration(
            "Serves /metrics (Prometheus text format) and /healthz over HTTP, for a local scraper.",
            "observability.prometheus-enabled",
            false
        ),
        migration(
            "Address and port of that listener. It has no authentication, so only a loopback address\n"
                + " (127.0.0.1, [::1], localhost) is accepted: the proxy refuses to start with any other one.",
            "observability.prometheus-bind",
            "127.0.0.1:9225"
        ),

        // [redis]
        migration(
            "Should Redis be used to communicate between multiple Velocity proxies?",
            "redis.enabled",
            false
        ),
        migration(
            "What address should be used to link all Velocity functions to Redis?",
            "redis.host",
            "127.0.0.1"
        ),
        migration(null, "redis.port", 6379),
        migration(
            "Leave the username blank if you do not have a defined username for your Redis database.",
            "redis.username",
            ""
        ),
        migration(null, "redis.password", ""),
        migration(null, "redis.use-ssl", false),
        migration(
            "The ID of this proxy, only needed for multi-proxy setups.\n"
                + " Leave blank if you do not use Redis. Your server will not start if this is blank and Redis is on.",
            "redis.proxy-id",
            ""
        ),
        migration(
            "What to do with a login when Redis does not answer the duplicate-login check within 500 ms.\n"
                + " \"deny\" refuses it: a player can never be online on two proxies at once.\n"
                + " \"allow\" lets the player in unchecked, at the risk of one player holding two sessions.",
            "redis.login-check-failure",
            "deny"
        ),

        // [queue]
        migration(
            "Whether the queue system is enabled. This will fully unregister\n"
                + " all permissions, commands, and this feature as a whole.",
            "queue.enabled",
            false
        ),
        migration(
            "The list of IDs of the proxy (in order of priority) that should handle and maintain the queue.\n"
                + " Only necessary in multi-proxy setup, leave blank otherwise.",
            "queue.master-proxy-ids",
            List.of("")
        ),
        migration(
            "The list of aliases for the \"/leavequeue\" command. The command will not be registered if this list is empty.",
            "queue.leave-queue-aliases",
            List.of("leavequeue", "dequeue")
        ),
        migration(
            "The list of aliases for the \"/queueadmin\" command. The command will not be registered if this list is empty.",
            "queue.queue-admin-aliases",
            List.of("queueadmin", "qadmin")
        ),
        migration(
            "The list of servers that should not have the queue system enabled.",
            "queue.no-queue-servers",
            List.of("lobby")
        ),
        migration(
            "If the disconnect reason contains any part of this filter, it will remove the player from the queue immediately.\n"
                + " This is case-sensitive.",
            "queue.banned-reason",
            List.of("banned")
        ),
        migration(
            "Whether the user should be capable of entering multiple queues at once.",
            "queue.allow-multi-queue",
            false
        ),
        migration(
            "How long the queue system should wait before sending each user to a server (in seconds).",
            "queue.send-delay",
            1.0
        ),
        migration(
            "How long the queue should wait before resuming sending players to servers after a backend comes back online.",
            "queue.queue-delay",
            0.0
        ),
        migration(
            "How long the queue system should wait before updating or sending a new action bar, chat message, or title/subtitle.",
            "queue.message-delay",
            1.0
        ),
        migration(
            "How often to ping backend servers to check if they're online.",
            "queue.backend-ping-interval",
            1.0
        ),
        migration(
            "The number of tries a user should be sent to a server before being removed from the queue.",
            "queue.max-send-retries",
            10
        ),
        migration(
            "Whether the player should be removed from their previous queue when switching servers.",
            "queue.remove-player-on-server-switch",
            true
        ),
        migration(
            "The server that players will be moved to when they enter ANY queue.\n"
                + " When a player queues for any server, they will be automatically sent to this server.\n"
                + " While in a queue and on this server, any server switch attempts will be blocked unless\n"
                + " the player has the velocity.queue.server-switch.bypass permission.\n"
                + " This is mutually exclusive with auto-queue-servers. Leave empty to disable.",
            "queue.queue-server",
            ""
        ),
        migration(
            "The server queue(s) a player is automatically entered into on their first proxy join.\n"
                + " Can be configured as a single string or a list of strings.",
            "queue.queue-on-join",
            List.of()
        ),
        migration(
            "Whether the kick message or indicator should be shown when you have failed to queue and join a specific server.",
            "queue.forward-kick-reason",
            true
        ),
        migration(
            "Whether users can enter a queue that is paused.",
            "queue.allow-paused-queue-joining",
            false
        ),
        migration(
            "Whether users should be automatically added back to the queue of their previously connected server.",
            "queue.queue-on-shutdown",
            true
        ),
        migration(
            "If true, players sent via BungeeCord Messaging channels are queued if the server has queueing enabled.\n"
                + " If false, they bypass the queue and are sent directly to the backend.",
            "queue.override-bungee-messaging",
            true
        ),

        // [proxy-addresses]
        migration(
            "Determines which fallback proxy to send players to.\n"
                + " Available options: \"first_found\", \"most_empty\", \"least_empty\", \"none\".",
            "proxy-addresses.dynamic-proxy-filter",
            "most_empty"
        ),

        // [forced-hosts]
        migration(
            "Whether to use the configured forced hosts as fallback (try) servers\n"
                + "if a player joins through a forced host that's configured.",
            "forced-hosts.forced-host-as-fallback",
            true
        ),

        new BtcAutoQueueServersMigration()
    );
  }

  private static ConfigurationMigration migration(String comment, String key, Object defaultValue) {
    if (comment != null) {
      comment = Stream.of(comment.split("\n"))
          .map(line -> !line.startsWith(" ") ? (" " + line) : line)
          .collect(Collectors.joining("\n"));
    }

    return new BtcSimpleMigration(key, defaultValue, comment);
  }
}
