# client-policy

Velocity plugin `btc-client-policy` (Sentinel volet 14, tasks 14.2 to 14.5). Pure Velocity API.

| Job | What it does | Trust |
|---|---|---|
| Consent (14.2) | On entering a backend, sends the Xaero codes as a **system message** and the `voxelmap:settings` plugin message. Re-sent when the next backend's consent differs; a backend that declares nothing sends Xaero's `reset`. | Honest mods apply it to themselves |
| Observation (14.3) | Per session: brand + registered channels, one structured line on the logger `btc.clientpolicy` at first declaration and at every addition. "Brand vanilla + non-`minecraft` channels" is journalled as `event=inconsistency ... action=none`. `/clientpolicy <player>` shows it (permission `btc.clientpolicy.view`). | Declarative: a clue |
| Refusal (14.4) | `deny` list in `config.yml`; `mode: dry-run` (default, journals `would-refuse`) or `enforce` (disconnects with the rule's message, never a ban). Bedrock and holders of `btc.clientpolicy.bypass` are exempt. | Catches only clients that declare themselves |
| Bedrock (14.5) | Nothing is sent, journal says `origin BEDROCK, client policy n/a`. Origin comes from `BridgePlatformSources` (installed by `platform-resolver`); with no source the origin is `UNKNOWN`, not "Java". | |

## Configuration

`plugins/btc-client-policy/config.yml`, written on first start from `src/main/resources/config.yml`
(dry-run, fair play, **empty deny list**). Any error switches the plugin off with an explicit log
message while the proxy starts. A deny pattern is a case-insensitive Java regular expression
matched against the **whole** brand or channel; loading is refused if one matches `fabric`,
`forge`, `neoforge`, `vanilla` or `quilt`.

## Known limits (to settle on the bench, task 14.1)

- In this fork `PlayerChannelRegisterEvent` fires only in the play phase; a `minecraft:register`
  sent during the configuration phase (Fabric `c:register`) raises no event. The brand does fire
  in the configuration phase.
- The phase in which VoxelMap-Updated accepts `voxelmap:settings`, and whether Xaero swallows the
  message on 26.3, are not proven by source reading.
- Integration into `/sentinel profile` (backend command) is out of this module.
