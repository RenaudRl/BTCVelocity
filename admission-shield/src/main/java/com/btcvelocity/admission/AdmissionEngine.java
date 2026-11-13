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

package com.btcvelocity.admission;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import com.btcvelocity.admission.Verdict.Origin;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admission rule, free of any Velocity type so that it can be tested without a runtime.
 *
 * <p>The rule, in order:
 * <ol>
 *   <li>A connection of the proxy's {@code geyser} listener counts as coming from Geyser, even with
 *       an empty {@code geyser-addresses}. Its address is the player's real one (PROXY protocol):
 *       a Floodgate player of that listener is judged on it when Floodgate recorded no other.</li>
 *   <li>A Floodgate player coming from Geyser's own address is never judged on that address:
 *       every Bedrock player shares it, and a datacenter address for Geyser would refuse them
 *       all. At the pre-login the decision waits for the login; there, the address Floodgate
 *       recorded is judged if it is known, otherwise the player is admitted and the journal says
 *       "bedrock, ip r&eacute;elle inconnue". A connection from a Geyser address that is
 *       <em>not</em> a Floodgate player is an anomaly: it is journaled as such and judged on
 *       its address like any other.</li>
 *   <li>An address in no list is admitted, silently. So is every address while no list is loaded:
 *       an absent list never refuses anybody.</li>
 *   <li>A listed address in an exempted network is admitted (and journaled).</li>
 *   <li>A listed address of an exempted player is admitted (and journaled) &mdash; once the
 *       player is authenticated. At the pre-login, a name that some exemption could cover
 *       defers the decision to the login.</li>
 *   <li>A class that is only a signal ({@link IpClass#VPN}) is admitted in every mode and
 *       journaled, after the two exemptions above (see {@link #signalOf}).</li>
 *   <li>Otherwise the connection is refused, or only observed in {@code observe} mode.</li>
 * </ol>
 *
 * <p>Independently of the lists, the pre-login counts the attempt against its network (an IPv4
 * /24, an IPv6 /48): see {@link #decideRate}. That bound is the only state the engine holds.
 */
public final class AdmissionEngine {

  private static final Verdict NOT_CONCERNED =
      new Verdict(Action.ADMIT, Basis.CLEAN, Origin.DIRECT, null, null, null);

  private final AdmissionConfig config;
  private final IpClassifier classifier;
  private final IpClassifier shadow;
  private final AsnLookup asn;
  private final NetworkRateLimiter rate;

  /**
   * Creates the engine, with no new list version waiting.
   *
   * @param config the settings
   * @param classifier where an address is looked up
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier) {
    this(config, classifier, address -> Optional.empty());
  }

  /**
   * Creates the engine.
   *
   * @param config the settings
   * @param classifier where an address is looked up
   * @param shadow what the new list versions in quarantine would say; never refuses anybody
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier,
      final IpClassifier shadow) {
    this(config, classifier, shadow, Clock.systemUTC());
  }

  /**
   * Creates the engine.
   *
   * @param config the settings
   * @param classifier where an address is looked up
   * @param shadow what the new list versions in quarantine would say; never refuses anybody
   * @param clock the time the per-network bound counts attempts in
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier,
      final IpClassifier shadow, final Clock clock) {
    this(config, classifier, shadow, clock, AsnLookup.NONE, AsnLookup.NONE);
  }

  /**
   * Creates the engine, with the IP-to-ASN database.
   *
   * <p>The denied systems ({@code asn.deny-asns}) are consulted after the lists: an address a list
   * names keeps the list's class, and the system is only added to its verdict.
   *
   * @param config the settings
   * @param classifier where an address is looked up in the lists in use
   * @param shadow what the new list versions in quarantine would say; never refuses anybody
   * @param clock the time the per-network bound counts attempts in
   * @param asn the database in use: names the system of the verdicts that are written down, feeds
   *     the keyword signal and the grouping of the per-network bound
   * @param asnShadow the database version in quarantine, if any; never refuses anybody
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier,
      final IpClassifier shadow, final Clock clock, final AsnLookup asn,
      final AsnLookup asnShadow) {
    this(config, classifier, shadow, clock, asn, asnShadow,
        LoggerFactory.getLogger(AdmissionEngine.class));
  }

  /**
   * Creates the engine, with the IP-to-ASN database and the logger the grouping reports to.
   *
   * @param config the settings
   * @param classifier where an address is looked up in the lists in use
   * @param shadow what the new list versions in quarantine would say; never refuses anybody
   * @param clock the time the per-network bound counts attempts in
   * @param asn the database in use
   * @param asnShadow the database version in quarantine, if any; never refuses anybody
   * @param logger where a system left out of the grouping is reported
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier,
      final IpClassifier shadow, final Clock clock, final AsnLookup asn,
      final AsnLookup asnShadow, final Logger logger) {
    this.config = config;
    this.classifier = classifier.orElse(new AsnClassifier(asn, config.asn()));
    this.shadow = shadow.orElse(new AsnClassifier(asnShadow, config.asn()));
    this.asn = asn;
    NetworkRateLimiter.Grouping grouping = NetworkRateLimiter.Grouping.byPrefix(
        config.networkRate().ipv4Prefix(), config.networkRate().ipv6Prefix());
    if (config.networkRate().groupHostingByAsn()) {
      grouping = NetworkRateLimiter.Grouping.byHostingAsn(asn,
          new HostingGroups(config.asn(), asn, config.rateMode() == AdmissionConfig.Mode.ENFORCE,
              config.networkRate().maxAsnSharePercent(), logger), grouping);
    }
    this.rate = new NetworkRateLimiter(config.networkRate(), clock, grouping);
  }

  /**
   * The per-network bound, for the staff command to report on.
   *
   * @return the limiter this engine counts attempts with
   */
  public NetworkRateLimiter rateLimiter() {
    return this.rate;
  }

  /**
   * Whether an authenticated player is one the new list versions in quarantine would refuse
   * <em>in addition</em> to what the versions in use refuse. Exemptions and the Geyser rule apply
   * as they would for a real decision.
   *
   * @param authenticated the connection with its authenticated identity
   * @return the verdict the new versions would give, or empty when they would add no refusal
   */
  public Optional<Verdict> wouldRefuseInAddition(final Attempt authenticated) {
    final Verdict waiting = decide(authenticated, Phase.LOGIN, this.shadow);
    if (waiting.basis() != Basis.LISTED) {
      return Optional.empty();
    }
    final Verdict inUse = decide(authenticated, Phase.LOGIN, this.classifier);
    return inUse.basis() == Basis.LISTED ? Optional.empty() : Optional.of(waiting);
  }

  /**
   * Judges a connection.
   *
   * @param attempt what is known about it
   * @param phase where in the connection this is
   * @return the verdict
   */
  public Verdict decide(final Attempt attempt, final Phase phase) {
    return decide(attempt, phase, this.classifier);
  }

  private Verdict decide(final Attempt attempt, final Phase phase,
      final IpClassifier lists) {
    return named(decideOn(attempt, phase, lists));
  }

  /** A verdict that gets written down says which autonomous system the address belongs to. */
  private Verdict named(final Verdict verdict) {
    if (verdict.asn() != null || verdict.judged() == null || !verdict.isJournaled()) {
      return verdict;
    }
    return this.asn.lookup(verdict.judged()).map(verdict::withAsn).orElse(verdict);
  }

  /**
   * The signal for the staff: the address is in no list, but the name of its autonomous system
   * holds one of the configured keywords.
   */
  private Optional<Verdict> keywordSignal(final InetAddress judged, final Origin origin) {
    if (this.config.asn().observeKeywords().isEmpty()) {
      return Optional.empty();
    }
    return this.asn.lookup(judged)
        .filter(info -> this.config.asn().keywordIn(info.name()).isPresent())
        .map(info -> new Verdict(Action.ADMIT, Basis.ASN_KEYWORD, origin, judged, null, null,
            info));
  }

  /** The proxy's own Geyser listener counts as a Geyser address, even with an empty list. */
  private boolean fromGeyser(final Attempt attempt) {
    return attempt.viaGeyserListener() || this.config.geyserAddresses().contains(attempt.remote());
  }

  /**
   * The real address of a Floodgate player, or {@code null} when it is unknown or is Geyser's own.
   */
  private @Nullable InetAddress bedrockReal(final Attempt attempt) {
    InetAddress real = attempt.bedrockReal();
    if (real == null && attempt.viaGeyserListener()) {
      // PROXY protocol: the address of this connection already is the player's real one.
      real = attempt.remote();
    }
    return real == null || this.config.geyserAddresses().contains(real) ? null : real;
  }

  private Verdict decideOn(final Attempt attempt, final Phase phase,
      final IpClassifier lists) {
    InetAddress judged = attempt.remote();
    Origin origin = Origin.DIRECT;
    if (fromGeyser(attempt)) {
      if (phase == Phase.PRE_LOGIN) {
        return new Verdict(Action.DEFER, Basis.GEYSER_ORIGIN, Origin.GEYSER_UNCONFIRMED, null,
            null, null);
      }
      if (attempt.floodgatePlayer()) {
        final InetAddress real = bedrockReal(attempt);
        if (real == null) {
          return new Verdict(Action.ADMIT, Basis.BEDROCK_IP_UNKNOWN, Origin.GEYSER_FLOODGATE, null,
              null, null);
        }
        judged = real;
        origin = Origin.GEYSER_FLOODGATE;
      } else {
        // Only Floodgate players are entitled to the Geyser exception. Anything else coming from
        // that address (a Java client on the Geyser host, a spoofed forward) is judged like anyone.
        origin = Origin.GEYSER_ADDRESS_NOT_FLOODGATE;
      }
    }

    final Optional<IpClassifier.Match> match = lists.classify(judged);
    if (match.isEmpty()) {
      if (origin != Origin.GEYSER_ADDRESS_NOT_FLOODGATE) {
        final Optional<Verdict> signal = keywordSignal(judged, origin);
        if (signal.isPresent()) {
          return signal.get();
        }
      }
      final Basis basis =
          origin == Origin.GEYSER_ADDRESS_NOT_FLOODGATE ? Basis.GEYSER_ADDRESS_NOT_FLOODGATE
              : Basis.CLEAN;
      return new Verdict(Action.ADMIT, basis, origin, judged, null, null);
    }
    final IpClass ipClass = match.get().ipClass();
    final String sourceId = match.get().sourceId();
    if (!ipClass.refusable()) {
      return signalOf(attempt, phase, judged, origin, ipClass, sourceId);
    }

    final Exemption exemption = exemptionOf(attempt, phase, judged);
    if (exemption != Exemption.NONE) {
      final Action action = exemption == Exemption.AWAITING_IDENTITY ? Action.DEFER : Action.ADMIT;
      return new Verdict(action, exemption.basis, origin, judged, ipClass, sourceId);
    }

    final AdmissionConfig.Mode mode =
        ipClass == IpClass.ASN ? this.config.asnMode() : this.config.mode();
    final Action action = mode == AdmissionConfig.Mode.ENFORCE ? Action.REFUSE : Action.OBSERVE;
    return new Verdict(action, Basis.LISTED, origin, judged, ipClass, sourceId);
  }

  /** What the exemptions say about a connection, in the order they are applied. */
  private enum Exemption {
    /** The judged address is in an exempted network. */
    NETWORK(Basis.EXEMPT_NETWORK),
    /** The authenticated player is exempted (login only). */
    PLAYER(Basis.EXEMPT_PLAYER),
    /** At the pre-login, a nominative exemption might cover the name once authenticated. */
    AWAITING_IDENTITY(Basis.AWAITING_IDENTITY),
    /** No exemption applies. */
    NONE(Basis.CLEAN);

    private final Basis basis;

    Exemption(final Basis basis) {
      this.basis = basis;
    }
  }

  private Exemption exemptionOf(final Attempt attempt, final Phase phase,
      final InetAddress judged) {
    if (this.config.exemptions().coversAddress(judged)) {
      return Exemption.NETWORK;
    }
    if (phase == Phase.PRE_LOGIN) {
      return this.config.exemptions().mayCoverLater(attempt.username())
          ? Exemption.AWAITING_IDENTITY : Exemption.NONE;
    }
    return attempt.uuid() != null
        && this.config.exemptions().coversPlayer(attempt.username(), attempt.uuid())
        ? Exemption.PLAYER : Exemption.NONE;
  }

  /**
   * An address of a class that is only a signal ({@link IpClass#VPN}): admitted whatever the mode,
   * journaled and told to the staff, but only once the account is authenticated: at the pre-login
   * the name is only typed, and a bot behind a VPN would otherwise write a journal line and a staff
   * message under any name it likes. An exempted network or player is not signalled.
   */
  private Verdict signalOf(final Attempt attempt, final Phase phase, final InetAddress judged,
      final Origin origin, final IpClass ipClass, final String sourceId) {
    final Exemption exemption = exemptionOf(attempt, phase, judged);
    if (exemption == Exemption.NETWORK || exemption == Exemption.PLAYER) {
      // An anomaly stays one: an exempted address reaching the Geyser listener unannounced.
      final Basis basis = origin == Origin.GEYSER_ADDRESS_NOT_FLOODGATE
          ? Basis.GEYSER_ADDRESS_NOT_FLOODGATE : Basis.CLEAN;
      return new Verdict(Action.ADMIT, basis, origin, judged, null, null);
    }
    if (phase == Phase.PRE_LOGIN) {
      return new Verdict(Action.DEFER, Basis.AWAITING_IDENTITY, origin, judged, ipClass,
          sourceId);
    }
    return new Verdict(Action.ADMIT, Basis.VPN_SIGNAL, origin, judged, ipClass, sourceId);
  }

  /**
   * What the login must do about a connection, now that the account is authenticated.
   *
   * <p>The pre-login is judged again from what it knew (the address, the name). If it deferred,
   * the login decides. If it refused, the refusal stands and is applied again: another plugin
   * listening to the pre-login may have turned the refusal back into an admission, and the
   * login is the last point where the proxy can still close the door. If it admitted or only
   * observed, it has already said everything and nothing more is written.
   *
   * @param authenticated the connection with its authenticated identity
   * @return the verdict to journal and apply at the login, or empty when there is none
   */
  public Optional<Verdict> decideAtLogin(final Attempt authenticated) {
    final Optional<Verdict> fromLists = decideListsAtLogin(authenticated);
    final Optional<Verdict> fromRate = decideRateAtLogin(authenticated);
    if (fromRate.isEmpty()) {
      return fromLists;
    }
    return fromLists.map(lists -> stronger(lists, fromRate.get())).or(() -> fromRate);
  }

  private Optional<Verdict> decideListsAtLogin(final Attempt authenticated) {
    final Verdict preLogin = decide(
        Attempt.claimed(authenticated.username(), authenticated.remote(),
            authenticated.viaGeyserListener()), Phase.PRE_LOGIN);
    return switch (preLogin.action()) {
      case DEFER -> Optional.of(decide(authenticated, Phase.LOGIN));
      case REFUSE -> Optional.of(preLogin);
      case ADMIT, OBSERVE -> Optional.empty();
    };
  }

  /**
   * Judges a connection at the pre-login: the lists, and the bound on attempts per network.
   *
   * <p>Every pre-login spends one token of its network, whatever the lists say, so that the
   * count is the truth about the traffic (and an {@code observe} run measures it). When both rules
   * have something to say, the heavier action wins (refuse, then observe, then defer, then admit)
   * and the lists win a tie.
   *
   * @param attempt what is known about the connection: its address and the name it claims
   * @return the verdict to journal and apply
   */
  public Verdict decidePreLogin(final Attempt attempt) {
    final Verdict fromLists = decide(attempt, Phase.PRE_LOGIN);
    return stronger(fromLists, decideRate(attempt));
  }

  /**
   * Spends one attempt of the connection's network and says whether it is over the limit.
   *
   * <p>Never applied to: an address that is not public (the proxy's own neighbours, carrier-grade
   * NAT range), an address of {@code geyser-addresses} (every Bedrock player arrives from it), an
   * exempted network. A connection of the proxy's Geyser listener <em>is</em> counted: with the
   * PROXY protocol its address is the player's real one, not Geyser's. A player exemption needs the authenticated name <em>and</em> UUID, and the
   * pre-login only has a name anyone can type; a name some exemption carries therefore does not
   * excuse the attempt, it defers the decision to the login ({@link #decideRateAtLogin}), exactly
   * as the lists do. In {@code observe} mode nothing is deferred: the attempt is observed.
   *
   * @param attempt the connection at the pre-login
   * @return {@code REFUSE}, {@code OBSERVE} or {@code DEFER} with basis {@code RATE_NETWORK} or
   *     {@code AWAITING_IDENTITY} when over the limit, a clean {@code ADMIT} otherwise
   */
  public Verdict decideRate(final Attempt attempt) {
    final Optional<InetAddress> network = rateSubject(attempt);
    if (network.isEmpty() || this.rate.tryAcquire(network.get())) {
      return NOT_CONCERNED;
    }
    return overTheLimit(attempt);
  }

  private Verdict overTheLimit(final Attempt attempt) {
    if (this.config.rateMode() == AdmissionConfig.Mode.OBSERVE) {
      return rateVerdict(Action.OBSERVE, Basis.RATE_NETWORK, attempt);
    }
    if (this.config.exemptions().mayCoverLater(attempt.username())) {
      return rateVerdict(Action.DEFER, Basis.AWAITING_IDENTITY, attempt);
    }
    return rateVerdict(Action.REFUSE, Basis.RATE_NETWORK, attempt);
  }

  /**
   * Settles at the login an attempt over the limit that the pre-login deferred for a name some
   * exemption carries: the authenticated player is let in if an exemption names this name and
   * UUID, and refused if the network is still over its limit and none does.
   *
   * <p>Spends nothing. A network that is not over its limit at the login is admitted: only a name
   * that an exemption carries gets here, and the bucket was only spent once, at the pre-login.
   *
   * @param authenticated the connection with its authenticated identity
   * @return the refusal, or empty when there is none
   */
  public Optional<Verdict> decideRateAtLogin(final Attempt authenticated) {
    if (this.config.rateMode() == AdmissionConfig.Mode.OBSERVE
        || !this.config.exemptions().mayCoverLater(authenticated.username())
        || this.config.exemptions().coversPlayer(authenticated.username(), authenticated.uuid())) {
      return Optional.empty();
    }
    return rateSubject(authenticated)
        .filter(this.rate::isLimited)
        .map(network -> rateVerdict(Action.REFUSE, Basis.RATE_NETWORK, authenticated));
  }

  /** The address the bound counts, or empty when this connection is never counted. */
  private Optional<InetAddress> rateSubject(final Attempt attempt) {
    final InetAddress remote = attempt.remote();
    if (!this.config.networkRate().enabled()) {
      return Optional.empty();
    }
    final Optional<InetAddress> network = AddressPolicy.classifiable(remote);
    if (network.isEmpty()) {
      return Optional.empty();
    }
    final InetAddress counted = network.get();
    final boolean spared = this.config.geyserAddresses().contains(remote)
        || this.config.geyserAddresses().contains(counted)
        || this.config.exemptions().coversAddress(remote)
        || this.config.exemptions().coversAddress(counted);
    return spared ? Optional.empty() : network;
  }

  private Verdict rateVerdict(final Action action, final Basis basis, final Attempt attempt) {
    return named(new Verdict(action, basis, Origin.DIRECT, attempt.remote(), null, null));
  }

  /**
   * The address the account links judge an authenticated login on, or empty when that rule stays
   * silent: links off, an address that is not public, a Bedrock player whose real address is
   * unknown, an exempted network or player. A Floodgate player is judged on its real address; a
   * connection from Geyser that is not a Floodgate player on the address it came from.
   *
   * @param authenticated the login
   * @return the address, unwrapped like the lists read it
   */
  public Optional<InetAddress> linkSubject(final Attempt authenticated) {
    if (!this.config.links().enabled() || authenticated.uuid() == null) {
      return Optional.empty();
    }
    InetAddress judged = authenticated.remote();
    if (fromGeyser(authenticated) && authenticated.floodgatePlayer()) {
      judged = bedrockReal(authenticated);
      if (judged == null) {
        return Optional.empty();
      }
    }
    if (exemptionOf(authenticated, Phase.LOGIN, judged) != Exemption.NONE) {
      return Optional.empty();
    }
    return AddressPolicy.classifiable(judged);
  }

  /**
   * The links of an entering account, from what the store knows around it. Only the accounts seen
   * on ITS address or ITS network are linked: there is no transitivity.
   *
   * @param self the entering account
   * @param facts the sightings around it and its household
   * @param now the time the windows are counted back from
   * @return one link per other account, the strongest that applies
   */
  public List<Link> linksOf(final UUID self, final LinkFacts facts, final Instant now) {
    final LinkSettings settings = this.config.links();
    final boolean shared = accountsOnAddress(self, facts, now) > settings.sharedAddressAccounts();
    final Instant strongSince = now.minus(settings.strongWindow());
    final Instant weakSince = now.minus(settings.weakWindow());
    final Instant addressSince = now.minus(LinkSettings.ADDRESS_HISTORY);
    final Map<UUID, Link.Strength> links = new LinkedHashMap<>();
    facts.onAddress().forEach((account, seen) -> {
      if (!account.equals(self) && !seen.isBefore(addressSince)) {
        links.put(account, facts.household().contains(account) ? Link.Strength.HOUSEHOLD
            : !shared && !seen.isBefore(strongSince) ? Link.Strength.STRONG : Link.Strength.WEAK);
      }
    });
    facts.onNetwork().forEach((account, seen) -> {
      if (!account.equals(self) && !seen.isBefore(weakSince) && !links.containsKey(account)) {
        links.put(account, facts.household().contains(account) ? Link.Strength.HOUSEHOLD
            : Link.Strength.WEAK);
      }
    });
    return links.entrySet().stream().map(link -> new Link(link.getKey(), link.getValue()))
        .toList();
  }

  /**
   * Judges an authenticated login on its account links. Pure: the facts and the bans are read
   * beforehand.
   *
   * <ul>
   *   <li>A STRONG link to an account under a network ban in force: refused ({@code enforce}) or
   *       observed, {@value Verdict#LINKED_BAN_CODE}.</li>
   *   <li>A WEAK link to such an account: admitted, journaled, told to the staff.</li>
   *   <li>More distinct accounts on the address than {@code max-accounts-per-household} (the
   *       entrant's household counted once): admitted, journaled, told to the staff.</li>
   *   <li>Otherwise, or when the banned account is of the entrant's declared household: a clean
   *       admission.</li>
   * </ul>
   *
   * @param authenticated the login
   * @param judged the address from {@link #linkSubject}
   * @param facts the sightings around the account and its household
   * @param activeBans the linked accounts under a network ban in force, with the ban's id
   * @param now the time the windows are counted back from
   * @return the verdict
   */
  public Verdict decideLinks(final Attempt authenticated, final InetAddress judged,
      final LinkFacts facts, final Map<UUID, Long> activeBans, final Instant now) {
    final UUID self = authenticated.uuid();
    final Origin origin = fromGeyser(authenticated) && authenticated.floodgatePlayer()
        ? Origin.GEYSER_FLOODGATE : Origin.DIRECT;
    final int accounts = accountsOnAddress(self, facts, now);
    Verdict weak = null;
    for (Link link : linksOf(self, facts, now)) {
      final Long ban = activeBans.get(link.account());
      if (ban == null || link.strength() == Link.Strength.HOUSEHOLD) {
        continue;
      }
      final LinkEvidence evidence =
          new LinkEvidence(link.strength(), link.account(), ban, accounts);
      if (link.strength() == Link.Strength.STRONG) {
        final Action action = this.config.linksMode() == AdmissionConfig.Mode.ENFORCE
            ? Action.REFUSE : Action.OBSERVE;
        return named(new Verdict(action, Basis.LINKED_BAN, origin, judged, null, null, null,
            evidence));
      }
      if (weak == null) {
        weak = new Verdict(Action.ADMIT, Basis.LINKED_ACCOUNT, origin, judged, null, null, null,
            evidence);
      }
    }
    if (weak != null) {
      return named(weak);
    }
    final Set<UUID> household = facts.household();
    final long ownHousehold = facts.onAddress().keySet().stream()
        .filter(account -> !account.equals(self) && household.contains(account)).count();
    if (accounts - ownHousehold > this.config.links().maxAccountsPerAddress()) {
      return named(new Verdict(Action.ADMIT, Basis.ACCOUNTS_PER_ADDRESS, origin, judged, null,
          null, null, new LinkEvidence(null, null, null, accounts)));
    }
    return new Verdict(Action.ADMIT, Basis.CLEAN, origin, judged, null, null);
  }

  /** The distinct accounts seen on the address over the address history, the entrant included. */
  private static int accountsOnAddress(final UUID self, final LinkFacts facts, final Instant now) {
    final Instant since = now.minus(LinkSettings.ADDRESS_HISTORY);
    final long others = facts.onAddress().entrySet().stream()
        .filter(seen -> !seen.getKey().equals(self) && !seen.getValue().isBefore(since)).count();
    return (int) others + 1;
  }

  private static Verdict stronger(final Verdict first, final Verdict second) {
    return weight(second.action()) > weight(first.action()) ? second : first;
  }

  private static int weight(final Action action) {
    return switch (action) {
      case ADMIT -> 0;
      case DEFER -> 1;
      case OBSERVE -> 2;
      case REFUSE -> 3;
    };
  }
}
