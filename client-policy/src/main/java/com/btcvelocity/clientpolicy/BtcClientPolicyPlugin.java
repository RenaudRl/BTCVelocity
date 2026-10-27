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
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    authors = {"BTC Studio"},
    dependencies = {@Dependency(id = "btc-platform-resolver", optional = true)})
public final class BtcClientPolicyPlugin {

  private static final String CONFIG_FILE = "config.yml";
  private static final Duration PLATFORM_CHECK_DELAY = Duration.ofSeconds(2);
  private static final MinecraftChannelIdentifier VOXELMAP_SETTINGS =
      MinecraftChannelIdentifier.create(VoxelMapSettingsPacket.NAMESPACE,
          VoxelMapSettingsPacket.NAME);

  private final ProxyServer proxy;
  private final Logger logger;
  private final Logger journal = LoggerFactory.getLogger(ObservationLog.LOGGER_NAME);
  private final Path dataDirectory;
  private final SessionRegistry sessions = new SessionRegistry();

  /** Null while the plugin is off (not yet loaded, or the configuration was refused). */
  private volatile ClientPolicyConfig config;

  @Inject
  public BtcClientPolicyPlugin(ProxyServer proxy, Logger logger,
                               @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  /** What a handler needs once it knows the event concerns a live, in-scope session. */
  private record Context(ClientPolicyConfig config, Player player, ClientOrigin origin,
                         ClientSession session) {
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

    // The platform resolver installs its source in its own initialisation, whose order relative to
    // this one is not guaranteed: look a moment later, when the answer is settled.
    this.proxy.getScheduler().buildTask(this, () -> warnIfBlind(loaded))
        .delay(PLATFORM_CHECK_DELAY).schedule();
  }

  private void warnIfBlind(ClientPolicyConfig loaded) {
    if (isBlindEnforcement(loaded, BridgePlatformSources.current())) {
      this.logger.warn("Bedrock indiscernable : tous les joueurs d'origine inconnue sont "
          + "exemptés du refus (aucune source de plateforme : {}).",
          BridgePlatformSources.current().describe());
    }
  }

  /**
   * Whether enforcement is on while nothing can tell a Bedrock player from a Java one.
   *
   * @param loaded the configuration
   * @param source the installed platform source
   * @return {@code true} when refusals can never fire, every origin being unknown
   */
  static boolean isBlindEnforcement(ClientPolicyConfig loaded, PlatformSource source) {
    return loaded.mode() == ClientPolicyConfig.Mode.ENFORCE && !source.canResolve();
  }

  @Subscribe
  public void onClientBrand(PlayerClientBrandEvent event) {
    enter(event.getPlayer()).ifPresent(context -> {
      context.session().recordBrand(event.getBrand())
          .ifPresent(change -> observe(context, change));
      enforce(context);
    });
  }

  @Subscribe
  public void onChannelRegister(PlayerChannelRegisterEvent event) {
    enter(event.getPlayer()).ifPresent(context -> {
      context.session().recordChannels(ids(event.getChannels()))
          .ifPresent(change -> observe(context, change));
      context.session().takeOverflowReport().ifPresent(dropped ->
          this.journal.warn(ObservationLog.overflow(who(context.player()), context.origin(),
              dropped)));
      enforce(context);
    });
  }

  @Subscribe
  public void onChannelUnregister(PlayerChannelUnregisterEvent event) {
    enter(event.getPlayer()).ifPresent(context ->
        context.session().removeChannels(ids(event.getChannels()))
            .ifPresent(change -> observe(context, change)));
  }

  /**
   * Sends the consent once the player is on a backend, at every server switch.
   *
   * @param event the completed server switch
   */
  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    enter(event.getPlayer()).ifPresent(context -> {
      var server = context.player().getCurrentServer();
      if (server.isEmpty()) {
        return;
      }

      String serverName = server.get().getServerInfo().getName();
      ConsentPlanner.Consent wanted = ConsentPlanner.desired(context.config(), serverName);
      ConsentPlanner.Delta delta =
          ConsentPlanner.plan(context.session().exchangeConsent(wanted), wanted);
      if (delta.isEmpty()) {
        return;
      }
      send(context.player(), delta);
      this.journal.info(ObservationLog.consent(who(context.player()), context.origin(),
          serverName, delta));
    });
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.sessions.close(event.getPlayer().getUniqueId(), event.getPlayer());
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

  /**
   * Common entry of every handler: the plugin is on, the connection is alive, and the session is
   * in scope. A Bedrock session gets its one explicit notice and stops here.
   */
  private Optional<Context> enter(Player player) {
    ClientPolicyConfig current = this.config;
    if (current == null) {
      return Optional.empty();
    }
    Optional<ClientSession> opened =
        this.sessions.open(player.getUniqueId(), player, player::isActive);
    if (opened.isEmpty()) {
      return Optional.empty();
    }

    ClientSession session = opened.get();
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    if (!origin.isPolicyApplicable()) {
      if (session.markBedrockNoted()) {
        this.journal.info(ObservationLog.bedrock(who(player)));
      }
      return Optional.empty();
    }
    return Optional.of(new Context(current, player, origin, session));
  }

  private List<String> describe(Player player) {
    ClientOrigin origin = resolveOrigin(BridgePlatformSources.current(), player.getUniqueId());
    ClientSession.Snapshot snapshot = this.sessions.find(player.getUniqueId(), player)
        .map(ClientSession::snapshot).orElse(null);
    RefusalPolicy.Verdict verdict = snapshot == null ? RefusalPolicy.Verdict.NOTHING
        : RefusalPolicy.evaluate(this.config, origin, () -> false, snapshot.brand(),
            snapshot.channels());
    return ProfileView.render(player.getUsername(), origin, snapshot, verdict);
  }

  private void observe(Context context, ClientSession.Observation change) {
    Player player = context.player();
    this.journal.info(ObservationLog.observation(who(player), context.origin(), change));

    ClientSession.Snapshot snapshot = context.session().snapshot();
    if (ClientCoherence.isVanillaWithModChannels(snapshot.brand(), snapshot.channels())
        && context.session().markInconsistencyNoted()) {
      this.journal.info(ObservationLog.inconsistency(who(player), context.origin(),
          snapshot.brand(), ClientCoherence.modChannels(snapshot.channels())));
    }
  }

  private void enforce(Context context) {
    Player player = context.player();
    SessionEnforcer.apply(context.config(), who(player), context.origin(), context.session(),
        () -> player.hasPermission(context.config().bypassPermission()), this.journal::info,
        this.journal::warn,
        message -> player.disconnect(MiniMessage.miniMessage().deserialize(message)));
  }

  private void send(Player player, ConsentPlanner.Delta delta) {
    for (String code : delta.xaeroMessages()) {
      // A system message, not a player chat line: Xaero reads it and hides it.
      // Adventure 5: the proxy sends sendMessage(Component) as a system message.
      player.sendMessage(Component.text(code));
    }
    if (delta.voxelJson() != null) {
      player.sendPluginMessage(VOXELMAP_SETTINGS, VoxelMapSettingsPacket.encode(delta.voxelJson()));
    }
  }

  private static List<String> ids(List<ChannelIdentifier> channels) {
    return channels.stream().map(ChannelIdentifier::getId).toList();
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