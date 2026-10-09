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

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The {@code asn} section, the ASN message and the modes it runs in. */
class AsnSettingsTest {

  private static AdmissionConfig parse(final String yaml, final List<String> warnings)
      throws IOException {
    return AdmissionConfig.parseYaml(new StringReader(yaml), warnings::add);
  }

  @Test
  void theDefaultsClassifyNothingAndObserve() {
    final AsnSettings asn = AdmissionConfig.defaults().asn();

    assertTrue(asn.enabled());
    assertEquals(AdmissionConfig.Mode.OBSERVE, asn.mode());
    assertEquals(Set.of(), asn.denyAsns());
    assertEquals(List.of(), asn.observeKeywords());
    assertEquals("https://iptoasn.com/data/ip2asn-combined.tsv.gz", asn.url().toString());
    assertEquals(32L * 1024 * 1024, asn.maxDownloadBytes());
    assertEquals(2_000_000, asn.maxRows());
    assertEquals(AdmissionConfig.Mode.OBSERVE, AdmissionConfig.defaults().asnMode());
    assertFalse(AdmissionConfig.defaults().networkRate().groupHostingByAsn());
  }

  @Test
  void theBundledTemplateSaysTheDefaultsWithoutAWarning() throws IOException {
    final List<String> warnings = new ArrayList<>();
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    final AdmissionConfig parsed = parse(template, warnings);

    assertEquals(List.of(), warnings);
    assertEquals(AdmissionConfig.defaults().asn(), parsed.asn());
    assertEquals(AdmissionConfig.defaults().messages(), parsed.messages());
    assertEquals(AdmissionConfig.defaults().networkRate(), parsed.networkRate());
  }

  @Test
  void everyValueIsReadFromTheFile() throws IOException {
    final AdmissionConfig parsed = parse("""
        asn:
          enabled: false
          mode: enforce
          deny-asns: [64496, "AS64497", "as64498"]
          observe-keywords: ["Hosting", "  VPN  ", "hosting"]
          url: https://mirror.example.org/ip2asn.tsv.gz
          max-download-megabytes: 16
          max-rows: 900000
        network-rate:
          group-hosting-by-asn: true
        """, new ArrayList<>());

    final AsnSettings asn = parsed.asn();
    assertFalse(asn.enabled());
    assertEquals(AdmissionConfig.Mode.ENFORCE, asn.mode());
    assertEquals(Set.of(64496L, 64497L, 64498L), asn.denyAsns());
    assertEquals(List.of("hosting", "vpn"), asn.observeKeywords(), "lower case, deduplicated");
    assertEquals("https://mirror.example.org/ip2asn.tsv.gz", asn.url().toString());
    assertEquals(16L * 1024 * 1024, asn.maxDownloadBytes());
    assertEquals(900_000, asn.maxRows());
    assertTrue(parsed.networkRate().groupHostingByAsn());
  }

  @Test
  void anAsNumberBeyondThirtyTwoBitsOrNotANumberIsReportedAndIgnored() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final AsnSettings asn = parse("""
        asn:
          deny-asns: [64496, 0, -5, 4294967296, "ASx", "", 4294967295]
        """, warnings).asn();

    assertEquals(Set.of(64496L, 4_294_967_295L), asn.denyAsns());
    assertEquals(5, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("asn.deny-asns"), warnings.get(0));
  }

  @Test
  void aKeywordShorterThanThreeCharactersIsIgnoredWithAWarning() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final AsnSettings asn = parse("""
        asn:
          observe-keywords: ["ab", "x", " a ", "vpn", ""]
        """, warnings).asn();

    assertEquals(List.of("vpn"), asn.observeKeywords());
    assertEquals(3, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("asn.observe-keywords"), warnings.get(0));
    assertTrue(warnings.get(0).contains("'ab'"), warnings.get(0));
    assertEquals(Optional.empty(), asn.keywordIn("Dell Technologies"),
        "a two-letter word would have matched half the registry");
  }

  @Test
  void aValueThatMakesNoSenseIsReportedAndReplacedByItsDefault() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final AsnSettings asn = parse("""
        asn:
          mode: strict
          url: http://iptoasn.com/data/ip2asn-combined.tsv.gz
          max-rows: 10
        """, warnings).asn();

    assertEquals(AdmissionConfig.Mode.OBSERVE, asn.mode(), "a typo never starts refusing");
    assertEquals(AsnSettings.DEFAULT_URL, asn.url(), "https only");
    assertEquals(2_000_000, asn.maxRows());
    assertEquals(3, warnings.size(), warnings.toString());
  }

  @Test
  void theModuleWideObserveAlsoHoldsTheAsnRefusalBack() throws IOException {
    final AdmissionConfig enforcing = parse("asn:\n  mode: enforce\n", new ArrayList<>());
    final AdmissionConfig dryRun = parse("mode: observe\nasn:\n  mode: enforce\n",
        new ArrayList<>());

    assertEquals(AdmissionConfig.Mode.ENFORCE, enforcing.asnMode());
    assertEquals(AdmissionConfig.Mode.OBSERVE, dryRun.asnMode());
  }

  @Test
  void theModeOfAVerdictIsTheOneOfTheRuleBehindIt() throws IOException {
    final AdmissionConfig config = parse("mode: enforce\nasn:\n  mode: observe\n",
        new ArrayList<>());
    final InetAddress host = InetAddress.ofLiteral("192.0.2.1");
    final Verdict asnVerdict = new Verdict(Verdict.Action.OBSERVE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, host, IpClass.ASN, "iptoasn:AS64496");
    final Verdict listVerdict = new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, host, IpClass.DATACENTER, "dc-list");

    assertEquals(AdmissionConfig.Mode.OBSERVE, config.modeFor(asnVerdict));
    assertEquals(AdmissionConfig.Mode.ENFORCE, config.modeFor(listVerdict));
  }

  @Test
  void aRefusedPlayerReadsTheAsnMessageWithItsCode() throws IOException {
    final Verdict verdict = new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, InetAddress.ofLiteral("192.0.2.1"), IpClass.ASN, "iptoasn:AS64496");

    assertTrue(AdmissionConfig.defaults().refusalText(verdict).contains("ADM-VPN-ASN"));
    assertEquals("<red>Non ({code})</red>".replace("{code}", "ADM-VPN-ASN"),
        parse("messages:\n  asn: \"<red>Non ({code})</red>\"\n", new ArrayList<>())
            .refusalText(verdict));
  }

  @Test
  void aListSourceCannotBeOfTheAsnClass() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final AdmissionConfig parsed = parse("""
        lists:
          sources:
            mine:
              class: asn
              url: https://example.org/list.txt
              file: mine.txt
        """, warnings);

    assertEquals(List.of(), parsed.sources());
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void aKeywordIsFoundInTheNameWhateverTheCase() throws IOException {
    final AsnSettings asn = parse("asn:\n  observe-keywords: [hosting, vpn]\n",
        new ArrayList<>()).asn();

    assertEquals(Optional.of("hosting"), asn.keywordIn("Alpha HOSTING BV"));
    assertEquals(Optional.of("vpn"), asn.keywordIn("Mullvad VPN AB"));
    assertEquals(Optional.empty(), asn.keywordIn("Orange S.A."));
    assertEquals(Optional.empty(), AsnSettings.defaults().keywordIn("Alpha Hosting"));
  }

  @Test
  void aSystemIsHostingWhenDeniedOrNamedByAKeyword() throws IOException {
    final AsnSettings asn = parse("""
        asn:
          deny-asns: [64496]
          observe-keywords: [hosting]
        """, new ArrayList<>()).asn();

    assertTrue(asn.isHosting(new AsnDatabase.Info(64496, "Anything", "FR")));
    assertTrue(asn.isHosting(new AsnDatabase.Info(1, "Some Hosting", "FR")));
    assertFalse(asn.isHosting(new AsnDatabase.Info(2, "Orange", "FR")));
  }

  @Test
  void theAsnDatabaseIsASourceNamedLikeItsFile() {
    final ListSource source = AsnSettings.defaults().source();

    assertEquals("iptoasn", source.id());
    assertEquals(IpClass.ASN, source.ipClass());
    assertEquals("ip2asn-combined.tsv.gz", source.fileName());
  }
}
