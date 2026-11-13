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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The X4BNet {@code vpn} list: a signal for the staff, never a reason to refuse anybody. */
class VpnSignalTest {

  private static final ListSource DATACENTER = new ListSource("dc", IpClass.DATACENTER,
      URI.create("https://lists.invalid/dc.txt"), "dc.txt");
  private static final ListSource VPN = new ListSource("vpn", IpClass.VPN,
      URI.create("https://lists.invalid/vpn.txt"), "vpn.txt");

  private static final InetAddress VPN_ONLY = ip("192.0.2.10");
  private static final InetAddress BOTH = ip("203.0.113.9");
  private static final InetAddress HOME = ip("198.51.100.77");
  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @TempDir
  Path directory;

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  /** The lists, loaded from disk like in production; the vpn list is configured FIRST. */
  private ListStore store(final String vpnFile, final String datacenterFile) throws IOException {
    if (vpnFile != null) {
      Files.writeString(this.directory.resolve("vpn.txt"), vpnFile);
    }
    if (datacenterFile != null) {
      Files.writeString(this.directory.resolve("dc.txt"), datacenterFile);
    }
    final ListStore store = new ListStore(this.directory, new RecordingLogger().logger(),
        Clock.systemUTC(), AdmissionConfig.DEFAULT_MAX_ENTRIES);
    store.loadAll(List.of(VPN, DATACENTER));
    return store;
  }

  @Test
  void aVpnAddressAloneIsAdmittedWithASignalWhateverTheMode() throws IOException {
    final ListStore store = store("192.0.2.0/24\n203.0.113.0/24\n", "203.0.113.0/24\n");

    for (String yaml : new String[] {"", "mode: observe"}) {
      final AdmissionEngine engine = new AdmissionEngine(config(yaml), store);
      final Verdict verdict = engine.decide(Attempt.claimed("Steve", VPN_ONLY)
          .authenticated("Steve", UUID.randomUUID(), false, null), Phase.LOGIN);

      assertEquals(Action.ADMIT, verdict.action(), yaml);
      assertEquals(Basis.VPN_SIGNAL, verdict.basis());
      assertEquals(IpClass.VPN, verdict.ipClass());
      assertEquals("vpn", verdict.sourceId());
      assertTrue(verdict.isJournaled(), "the signal is written down");
    }
  }

  @Test
  void theSignalWaitsForTheAuthenticatedNameNotTheTypedOne() throws IOException {
    final AdmissionEngine engine =
        new AdmissionEngine(config(""), store("192.0.2.0/24\n", null));

    final Verdict preLogin = engine.decidePreLogin(Attempt.claimed("AnyNameABotTypes", VPN_ONLY));
    final Optional<Verdict> login = engine.decideAtLogin(Attempt.claimed("Steve", VPN_ONLY)
        .authenticated("Steve", UUID.randomUUID(), false, null));

    assertEquals(Action.DEFER, preLogin.action());
    assertFalse(preLogin.isJournaled(), "nothing is written under a typed name");
    assertEquals(Basis.VPN_SIGNAL, login.orElseThrow().basis());
  }

  @Test
  void anExemptedAddressFromTheGeyserListenerWithoutFloodgateStaysAnAnomaly() throws IOException {
    final AdmissionEngine engine = new AdmissionEngine(config("""
        exemptions:
          networks:
            - network: 192.0.2.0/24
              expires: 2099-12-31
        """), store("192.0.2.0/24\n", null));

    final Verdict verdict = engine.decide(Attempt.claimed("Steve", VPN_ONLY, true)
        .authenticated("Steve", UUID.randomUUID(), false, null), Phase.LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.GEYSER_ADDRESS_NOT_FLOODGATE, verdict.basis());
    assertTrue(verdict.isJournaled());
  }

  @Test
  void aSignalIsJournaledInObserveModeSinceItNeverRefuses() {
    final AdmissionConfig enforce = config("");
    final Verdict vpn = new Verdict(Action.ADMIT, Basis.VPN_SIGNAL, Verdict.Origin.DIRECT,
        VPN_ONLY, IpClass.VPN, "vpn");
    final Verdict keyword = new Verdict(Action.ADMIT, Basis.ASN_KEYWORD, Verdict.Origin.DIRECT,
        VPN_ONLY, null, null);

    assertEquals(AdmissionConfig.Mode.ENFORCE, enforce.mode());
    assertEquals(AdmissionConfig.Mode.OBSERVE, enforce.modeFor(vpn));
    assertEquals(AdmissionConfig.Mode.OBSERVE, enforce.modeFor(keyword));
  }

  @Test
  void whyNamesTheSignalsAmongWhatIsRemembered() {
    final List<String> lines = WhyReport.explain("Nobody", new AdmissionJournal(
        new RecordingLogger().logger(), Clock.systemUTC()), address -> Optional.empty());

    assertTrue(lines.get(0).contains("signaux"), lines.get(0));
  }

  @Test
  void aVpnAddressThatIsAlsoADatacenterIsRefusedAsDatacenterWhateverTheOrder() throws IOException {
    final ListStore store = store("192.0.2.0/24\n203.0.113.0/24\n", "203.0.113.0/24\n");

    final Verdict verdict = new AdmissionEngine(config(""), store)
        .decide(Attempt.claimed("Steve", BOTH), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals(IpClass.DATACENTER, verdict.ipClass());
    assertEquals("dc", verdict.sourceId());
  }

  @Test
  void aVpnMatchGivesWayToAnyOtherClassifierThatRefusesTheAddress() {
    final IpClassifier vpn = address -> Optional.of(new IpClassifier.Match(IpClass.VPN, "vpn"));
    final IpClassifier denied =
        address -> Optional.of(new IpClassifier.Match(IpClass.ASN, "iptoasn:AS1"));
    final IpClassifier nothing = address -> Optional.empty();

    assertEquals(IpClass.ASN, vpn.orElse(denied).classify(VPN_ONLY).orElseThrow().ipClass());
    assertEquals(IpClass.VPN, vpn.orElse(nothing).classify(VPN_ONLY).orElseThrow().ipClass());
    assertEquals(IpClass.ASN, nothing.orElse(denied).classify(VPN_ONLY).orElseThrow().ipClass());
  }

  @Test
  void anAbsentOrEmptyVpnListChangesNothing() throws IOException {
    final ListStore absent = store(null, "203.0.113.0/24\n");
    final AdmissionEngine engine = new AdmissionEngine(config(""), absent);

    final Verdict clean = engine.decide(Attempt.claimed("Steve", VPN_ONLY), Phase.PRE_LOGIN);
    final Verdict datacenter = engine.decide(Attempt.claimed("Steve", BOTH), Phase.PRE_LOGIN);

    assertEquals(Basis.CLEAN, clean.basis());
    assertEquals(Action.ADMIT, clean.action());
    assertFalse(clean.isJournaled());
    assertEquals(Action.REFUSE, datacenter.action());

    final ListStore empty = store("", "203.0.113.0/24\n");
    assertEquals(Basis.CLEAN, new AdmissionEngine(config(""), empty)
        .decide(Attempt.claimed("Steve", VPN_ONLY), Phase.PRE_LOGIN).basis());
  }

  @Test
  void anExemptedNetworkOrPlayerIsNotSignalled() throws IOException {
    final ListStore store = store("192.0.2.0/24\n", null);
    final AdmissionEngine byNetwork = new AdmissionEngine(config("""
        exemptions:
          networks:
            - network: 192.0.2.0/24
              expires: 2099-12-31
        """), store);
    final AdmissionEngine byPlayer = new AdmissionEngine(config("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
        """), store);

    assertFalse(byNetwork.decide(Attempt.claimed("Steve", VPN_ONLY), Phase.LOGIN).isJournaled());
    assertEquals(Action.DEFER,
        byPlayer.decide(Attempt.claimed("StaffMember", VPN_ONLY), Phase.PRE_LOGIN).action());
    assertFalse(byPlayer.decide(Attempt.claimed("StaffMember", VPN_ONLY)
        .authenticated("StaffMember", STAFF_UUID, false, null), Phase.LOGIN).isJournaled());
    assertEquals(Basis.VPN_SIGNAL, byPlayer.decide(Attempt.claimed("Steve", VPN_ONLY)
        .authenticated("Steve", UUID.randomUUID(), false, null), Phase.LOGIN).basis());
  }

  @Test
  void theSignalIsJournaledAsAdmittedAndToldToTheStaffInItsOwnWords() {
    final Verdict verdict = new AdmissionEngine(config(""), address ->
        Optional.of(new IpClassifier.Match(IpClass.VPN, "vpn")))
        .decide(Attempt.claimed("Steve", VPN_ONLY), Phase.LOGIN);
    final AdmissionJournal journal = new AdmissionJournal(new RecordingLogger().logger(),
        Clock.systemUTC());
    final List<String> told = new ArrayList<>();

    final AdmissionJournal.Entry entry = journal.record(Attempt.claimed("Steve", VPN_ONLY), verdict,
        Phase.LOGIN, AdmissionConfig.Mode.ENFORCE).orElseThrow();
    StaffAlerts.forVpnSignals(Clock.systemUTC(), told::add).onRefusal(entry);

    assertTrue(entry.line().contains("decision=admitted"), entry.line());
    assertTrue(entry.line().contains("basis=vpn-signal"), entry.line());
    assertTrue(entry.line().contains("class=vpn"), entry.line());
    assertEquals(1, told.size());
    assertTrue(told.get(0).contains("Signal VPN"), told.get(0));
    assertFalse(told.get(0).contains("[Admission] Refus"), told.get(0));
  }

  @Test
  void theDefaultListsCarryTheVpnSourcesAndASwitchTurnsThemOff() {
    final List<ListSource> vpn = AdmissionConfig.defaultSources().stream()
        .filter(source -> source.ipClass() == IpClass.VPN).toList();

    assertEquals(List.of("x4bnet-vpn-ipv4", "x4bnet-vpn-ipv6"),
        vpn.stream().map(ListSource::id).toList());
    assertTrue(vpn.stream().allMatch(source -> source.url().getPath().contains("/output/vpn/")));
    assertEquals(vpn, config("").sources().stream()
        .filter(source -> source.ipClass() == IpClass.VPN).toList());
    assertTrue(config("lists:\n  vpn-signal: false\n").sources().stream()
        .noneMatch(source -> source.ipClass() == IpClass.VPN));
    assertEquals(3, config("lists:\n  vpn-signal: false\n").sources().size());
  }
}
