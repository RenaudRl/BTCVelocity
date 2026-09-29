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
 */
public final class AdmissionEngine {

  private final AdmissionConfig config;
  private final IpClassifier classifier;

  /**
   * Creates the engine.
   *
   * @param config the settings
   * @param classifier where an address is looked up
   */
  public AdmissionEngine(final AdmissionConfig config, final IpClassifier classifier) {
    this.config = config;
    this.classifier = classifier;
  }

  /**
   * Judges a connection.
   *
   * @param attempt what is known about it
   * @param phase where in the connection this is
   * @return the verdict
   */
  public Verdict decide(final Attempt attempt, final Phase phase) {
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

    final Optional<IpClassifier.Match> match = this.classifier.classify(judged);
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
    final Verdict preLogin = decide(
        Attempt.claimed(authenticated.username(), authenticated.remote()), Phase.PRE_LOGIN);
    return switch (preLogin.action()) {
      case DEFER -> Optional.of(decide(authenticated, Phase.LOGIN));
      case REFUSE -> Optional.of(preLogin);
      case ADMIT, OBSERVE -> Optional.empty();
    };
  }
}
