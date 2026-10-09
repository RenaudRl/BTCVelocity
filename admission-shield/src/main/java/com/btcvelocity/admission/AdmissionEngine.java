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
import java.util.Optional;

/**
 * The admission rule, free of any Velocity type so that it can be tested without a runtime.
 *
 * <p>The rule, in order:
 * <ol>
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
    this.config = config;
    this.classifier = classifier;
    this.shadow = shadow;
    this.rate = new NetworkRateLimiter(config.networkRate(), clock);
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
    InetAddress judged = attempt.remote();
    Origin origin = Origin.DIRECT;
    if (this.config.geyserAddresses().contains(attempt.remote())) {
      if (phase == Phase.PRE_LOGIN) {
        return new Verdict(Action.DEFER, Basis.GEYSER_ORIGIN, Origin.GEYSER_UNCONFIRMED, null,
            null, null);
      }
      if (attempt.floodgatePlayer()) {
        final InetAddress real = attempt.bedrockReal();
        if (real == null || this.config.geyserAddresses().contains(real)) {
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
      final Basis basis =
          origin == Origin.GEYSER_ADDRESS_NOT_FLOODGATE ? Basis.GEYSER_ADDRESS_NOT_FLOODGATE
              : Basis.CLEAN;
      return new Verdict(Action.ADMIT, basis, origin, judged, null, null);
    }
    final IpClass ipClass = match.get().ipClass();
    final String sourceId = match.get().sourceId();

    if (this.config.exemptions().coversAddress(judged)) {
      return new Verdict(Action.ADMIT, Basis.EXEMPT_NETWORK, origin, judged, ipClass, sourceId);
    }
    if (phase == Phase.PRE_LOGIN) {
      if (this.config.exemptions().mayCoverLater(attempt.username())) {
        return new Verdict(Action.DEFER, Basis.AWAITING_IDENTITY, origin, judged, ipClass,
            sourceId);
      }
    } else if (attempt.uuid() != null
        && this.config.exemptions().coversPlayer(attempt.username(), attempt.uuid())) {
      return new Verdict(Action.ADMIT, Basis.EXEMPT_PLAYER, origin, judged, ipClass, sourceId);
    }

    final Action action =
        this.config.mode() == AdmissionConfig.Mode.ENFORCE ? Action.REFUSE : Action.OBSERVE;
    return new Verdict(action, Basis.LISTED, origin, judged, ipClass, sourceId);
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
        Attempt.claimed(authenticated.username(), authenticated.remote()), Phase.PRE_LOGIN);
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
   * exempted network. A player exemption needs the authenticated name <em>and</em> UUID, and the
   * pre-login only has a name anyone can type; a name some exemption carries therefore does not
   * excuse the attempt, it defers the decision to the login ({@link #decideRateAtLogin}), exactly
   * as the lists do. In {@code observe} mode nothing is deferred: the attempt is observed.
   *
   * @param attempt the connection at the pre-login
   * @return {@code REFUSE}, {@code OBSERVE} or {@code DEFER} with basis {@code RATE_NETWORK} or
   *     {@code AWAITING_IDENTITY} when over the limit, a clean {@code ADMIT} otherwise
   */
  public Verdict decideRate(final Attempt attempt) {
    final Optional<InetAddress> network = rateSubject(attempt.remote());
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
    return rateSubject(authenticated.remote())
        .filter(this.rate::isLimited)
        .map(network -> rateVerdict(Action.REFUSE, Basis.RATE_NETWORK, authenticated));
  }

  /** The address the bound counts, or empty when this connection is never counted. */
  private Optional<InetAddress> rateSubject(final InetAddress remote) {
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

  private static Verdict rateVerdict(final Action action, final Basis basis,
      final Attempt attempt) {
    return new Verdict(action, basis, Origin.DIRECT, attempt.remote(), null, null);
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
