/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.clientpolicy;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.BridgePlatformSources;
import com.btcvelocity.api.bridge.PlatformSource;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The proxy's stance on the client a player runs (Sentinel volet 14).
 *
 * <p>Three jobs, from the most to the least trustworthy:
 * <ol>
 *   <li><strong>Consent</strong> (14.2): on entering a backend, tell the honest minimap mods what
 *       this server allows. A declared policy that honest mods apply to themselves.</li>
 *   <li><strong>Observation</strong> (14.3): keep the brand and the plugin channels each session
 *       declared and write them to a dedicated journal. Declarative, hence only a clue.</li>
 *   <li><strong>Refusal</strong> (14.4): disconnect a client whose declarations match a
 *       configured deny rule. Off by default ({@code dry-run}), never a ban, and blind to any
 *       client that hides its brand and channels.</li>
 * </ol>
 *
 * <p>The plugin fails closed on configuration: an invalid file switches the whole plugin off with
 * an explicit error, while the proxy starts normally.
 *
 * <p>Out of scope here: showing this profile in {@code /sentinel profile} (a backend command; it
 * needs the profile to cross {@code btc:bridge}).
 */
@Plugin(
    id = "btc-client-policy",
    name = "BTC Client Policy",
    version = "0.1",
    description = "Observes client brand and channels, sends mod consent, refuses listed clients.",
    authors = {"BTC Studio"})
public final class BtcClientPolicyPlugin {

  private static final String CONFIG_FILE = "config.yml";
  private static final MinecraftChannelIdentifier VOXELMAP_SETTINGS =
      MinecraftChannelIdentifier.create(VoxelMapSettingsPacket.NAMESPACE,
          VoxelMapSettingsPacket.NAME);

  private final ProxyServer proxy;
  private final Logger logger;
  private final Logger journal = LoggerFactory.getLogger(ObservationLog.LOGGER_NAME);
  private final Path dataDirectory;
  private final Map<UUID, ClientSession> sessions = new ConcurrentHashMap<>();

  /** Null while the plugin is off (not yet loaded, or the configuration was refused). */
  private volatile ClientPolicyConfig config;

  @Inject
  public BtcClientPolicyPlugin(ProxyServer proxy, Logger logger,
                               @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    ClientPolicyConfig.Parsed parsed = readConfiguration();
    if (parsed.config().isEmpty()) {
      this.logger.error("Client policy DISABLED: {} is invalid, fix it and restart the proxy.",
          this.dataDirectory.resolve(CONFIG_FILE));
      parsed.errors().forEach(error -> this.logger.error("  - {}", error));
      return;
    }

    ClientPolicyConfig loaded = parsed.config().get();
    this.config = loaded;
    var commands = this.proxy.getCommandManager();
    commands.register(commands.metaBuilder("clientpolicy").plugin(this).build(),
        new ClientPolicyCommand(this.proxy, () -> this.config, this::describe));
    this.logger.info("Client policy ready: mode {}, {} deny rule(s), xaero default '{}',"
            + " voxelmap {}.", loaded.mode() == ClientPolicyConfig.Mode.ENFORCE
            ? "enforce" : "dry-run", loaded.deny().size(),
        loaded.xaero().defaultCode().replace(String.valueOf((char) 0xA7), ""),
        loaded.voxelMap().enabled() ? "on" : "off");
  }

  @Subscribe
  public void onClientBrand(PlayerClientBrandEvent event) {
    ClientPolicyConfig current = this.config;
    Player player = event.getPlayer();
    if (current == null || !player.isActive()) {
      return;
    }
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    if (noteBedrock(player, origin)) {
      return;
    }
    ClientSession session = this.sessions.computeIfAbsent(player.getUniqueId(),
        id -> new ClientSession());
    session.recordBrand(event.getBrand())
        .ifPresent(change -> observe(player, origin, session, change));
    enforce(current, player, origin, session);
  }

  @Subscribe
  public void onChannelRegister(PlayerChannelRegisterEvent event) {
    ClientPolicyConfig current = this.config;
    Player player = event.getPlayer();
    if (current == null || !player.isActive()) {
      return;
    }
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    if (noteBedrock(player, origin)) {
      return;
    }
    ClientSession session = this.sessions.computeIfAbsent(player.getUniqueId(),
        id -> new ClientSession());
    List<String> ids = event.getChannels().stream().map(ChannelIdentifier::getId).toList();
    session.recordChannels(ids).ifPresent(change -> observe(player, origin, session, change));
    enforce(current, player, origin, session);
  }

  /**
   * Sends the consent once the player is on a backend, and again on a backend that differs.
   *
   * @param event the completed server switch
   */
  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    ClientPolicyConfig current = this.config;
    Player player = event.getPlayer();
    if (current == null || !player.isActive()) {
      return;
    }
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    if (noteBedrock(player, origin)) {
      return;
    }
    var server = player.getCurrentServer();
    if (server.isEmpty()) {
      return;
    }

    String serverName = server.get().getServerInfo().getName();
    ClientSession session = this.sessions.computeIfAbsent(player.getUniqueId(),
        id -> new ClientSession());
    ConsentPlanner.Consent wanted = ConsentPlanner.desired(current, serverName);
    ConsentPlanner.Delta delta = ConsentPlanner.delta(session.lastConsent(), wanted);
    session.rememberConsent(wanted);
    if (delta.isEmpty()) {
      return;
    }
    send(player, delta);
    this.journal.info(ObservationLog.consent(who(player), origin, serverName, delta));
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.sessions.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Resolves a session's platform through the source the platform resolver installed.
   *
   * @param source the installed source
   * @param player the account id
   * @return {@link ClientOrigin#UNKNOWN} when nothing can tell, never a guess
   */
  static ClientOrigin resolveOrigin(PlatformSource source, UUID player) {
    if (!source.canResolve()) {
      return ClientOrigin.UNKNOWN;
    }
    return source.platformOf(player)
        .map(platform -> platform == BridgeMessage.Platform.BEDROCK
            ? ClientOrigin.BEDROCK : ClientOrigin.JAVA)
        .orElse(ClientOrigin.UNKNOWN);
  }

  private List<String> describe(Player player) {
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    ClientSession session = this.sessions.get(player.getUniqueId());
    ClientSession.Snapshot snapshot = session == null ? null : session.snapshot();
    RefusalPolicy.Verdict verdict = snapshot == null ? RefusalPolicy.Verdict.NOTHING
        : RefusalPolicy.evaluate(this.config, origin, () -> false, snapshot.brand(),
            snapshot.channels());
    return ProfileView.render(player.getUsername(), origin, snapshot, verdict);
  }

  /** Writes the once-per-session Bedrock notice; true when the session is out of scope. */
  private boolean noteBedrock(Player player, ClientOrigin origin) {
    if (origin.isPolicyApplicable()) {
      return false;
    }
    ClientSession session = this.sessions.computeIfAbsent(player.getUniqueId(),
        id -> new ClientSession());
    if (session.markBedrockNoted()) {
      this.journal.info(ObservationLog.bedrock(who(player)));
    }
    return true;
  }

  private void observe(Player player, ClientOrigin origin, ClientSession session,
                       ClientSession.Observation change) {
    this.journal.info(ObservationLog.observation(who(player), origin, change));

    ClientSession.Snapshot snapshot = session.snapshot();
    if (ClientCoherence.isVanillaWithModChannels(snapshot.brand(), snapshot.channels())
        && session.markInconsistencyNoted()) {
      this.journal.info(ObservationLog.inconsistency(who(player), origin, snapshot.brand(),
          ClientCoherence.modChannels(snapshot.channels())));
    }
  }

  private void enforce(ClientPolicyConfig current, Player player, ClientOrigin origin,
                       ClientSession session) {
    if (current.deny().isEmpty()) {
      return;
    }
    ClientSession.Snapshot snapshot = session.snapshot();
    RefusalPolicy.Verdict verdict = RefusalPolicy.evaluate(current, origin,
        () -> player.hasPermission(current.bypassPermission()), snapshot.brand(),
        snapshot.channels());
    if (verdict.action() == RefusalPolicy.Action.NONE
        || !session.markRuleReported(verdict.rule().orElseThrow().label())) {
      return;
    }

    String line = ObservationLog.verdict(who(player), origin, verdict, current.mode());
    if (verdict.action() == RefusalPolicy.Action.REFUSE) {
      this.journal.warn(line);
      player.disconnect(MiniMessage.miniMessage().deserialize(verdict.rule().get().message()));
    } else {
      this.journal.info(line);
    }
  }

  @SuppressWarnings("deprecation")
  private void send(Player player, ConsentPlanner.Delta delta) {
    if (delta.xaeroMessage() != null) {
      // A system message, not a player chat line: Xaero reads it and hides it.
      player.sendMessage(Identity.nil(), Component.text(delta.xaeroMessage()),
          MessageType.SYSTEM);
    }
    if (delta.voxelJson() != null) {
      player.sendPluginMessage(VOXELMAP_SETTINGS, VoxelMapSettingsPacket.encode(delta.voxelJson()));
    }
  }

  private static ObservationLog.Who who(Player player) {
    return new ObservationLog.Who(player.getUsername(), player.getUniqueId());
  }

  /** Reads {@code config.yml}, writing the default file first when there is none. */
  private ClientPolicyConfig.Parsed readConfiguration() {
    Path file = this.dataDirectory.resolve(CONFIG_FILE);
    try {
      if (Files.notExists(file)) {
        Files.createDirectories(this.dataDirectory);
        try (InputStream template = BtcClientPolicyPlugin.class.getResourceAsStream(
            "/" + CONFIG_FILE)) {
          if (template == null) {
            return ClientPolicyConfig.parseYaml("");
          }
          Files.write(file, template.readAllBytes());
          this.logger.info("Wrote the default configuration to {} (dry-run, empty deny list).",
              file);
        }
      }
      return ClientPolicyConfig.parseYaml(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException failure) {
      return new ClientPolicyConfig.Parsed(Optional.empty(),
          List.of("cannot read " + file + ": " + failure.getMessage()));
    }
  }
}
