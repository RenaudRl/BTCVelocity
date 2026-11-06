# velocity-cloudnet-wake

Velocity plugin (`btc-cloudnet-wake`) that wakes a stopped CloudNet task when a player asks for it
(openspec `arrivee-limbo-et-reveil`, task 2.2). CloudNet stays the only orchestrator: no LazyMC.

## Flow

1. A player types `/server <task>` and no server of that task is registered in the proxy.
2. The plugin takes the command over, parks the player in the limbo (`BTC_ARRIVAL_LIMBO`) and starts
   the task through the CloudNet driver: a RUNNING instance is left alone, a PREPARED one is started,
   otherwise an instance is created from the task and started.
3. One *session* per task is the dedupe table: five simultaneous requests start the task once.
4. The session ends on the first registration of `<task>-<n>` in the proxy (the registry registers a
   service only once it is `RUNNING`, connected and `Online`): the waiting players are sent there.
5. It also ends when the start fails (`FAILED`, `DEFERRED`, unknown task) or after the timeout: the
   players get a message and the hold is dropped, and the arrival router's sweep (every 5 s) sends
   them back to a lobby.

While parked, a player answers `LimboHoldEvent` (velocity-api): the arrival router neither releases
them to a lobby nor shows its "no lobby available" text.

## Settings (environment of the proxy task)

| Variable | Default | Meaning |
| --- | --- | --- |
| `BTC_WAKE_TASKS` | unset (plugin off) | comma-separated CloudNet task names that may be woken |
| `BTC_WAKE_TIMEOUT_SECONDS` | `120` | longest wait before the player goes back to a lobby |
| `BTC_ARRIVAL_LIMBO` | unset (plugin off) | the waiting room, shared with `btc-arrival-router` |
| `BTC_ARRIVAL_SERVERS` | unset (plugin off, WARN at start) | the router policy; without it the router runs no limbo sweep |

Texts: `messages.properties` in the plugin's data directory (MiniMessage, `<task>` and `<seconds>`),
created with the defaults on first start; a removed key falls back to its default.

## Limits

- Only `/server <task>` is taken over. Velocity cannot connect a player to a server it does not
  know, so a backend `Connect` request or a `btc:bridge` transfer to a stopped task still fails.
- The requested name is the task name; a registered `<task>-<n>` is chosen by name order.
- No cooldown after a failed start: each new request retries.
- Needs `btc-arrival-router` (limbo sweep) to bring a player back to a lobby when the wait ends.
- Known limits, not handled: sessions are per proxy (with several proxies each one wakes the task
  on its own; the node's RUNNING check only softens it); no grace delay after a registration; an
  instance that stays RUNNING but never turns Online is waited for until the timeout; a player who
  leaves the limbo for another server while waiting is still pulled to the woken server.
