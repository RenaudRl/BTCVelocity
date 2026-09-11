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

package com.btcvelocity.proxy.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Autorité du proxy sur les permissions (PERM-027).
 *
 * <p>Cette classe fixe une politique, pas des détails : elle dit qui décide quand plusieurs sources
 * peuvent répondre. Les quatre situations couvertes ici sont celles où un choix est réellement fait
 * — snapshot absent, snapshot chargé et accordé, refus explicite, node non couvert — et chacune se
 * solde par un comportement observable différent. Sans ces tests, la politique n'existe que dans des
 * commentaires, et un refactor peut la retourner sans qu'aucun test ne bronche.
 *
 * <p><b>La politique tranchée.</b> Le proxy est autoritaire quand le mode natif est actif : pendant
 * le chargement asynchrone d'un snapshot il refuse, et il ne consulte la source Velocity que pour un
 * node qu'aucune assignation ne couvre. Se replier sur Velocity pendant le chargement
 * transformerait un délai d'entrée-sortie transitoire en autorisation, ce qui est précisément
 * l'erreur qu'un système de permissions ne doit jamais commettre : il vaut mieux refuser à tort une
 * fois qu'accorder à tort une fois.
 *
 * <p><b>Écart assumé avec Paper.</b> Côté Paper, l'évaluateur accorde à un opérateur tout node
 * qu'aucune assignation ne couvre (`operator-default`, sémantique de `PermissionDefault.OP`). Le
 * proxy n'a aucune notion d'opérateur — il n'a pas d'`op.json` et ne voit pas celui des backends —
 * donc un administrateur opérateur sur Paper n'obtient rien de ce fait au proxy. Ce n'est pas une
 * incohérence à corriger mais une frontière : les droits d'un opérateur sont locaux au serveur qui
 * les accorde, et les faire traverser le proxy reviendrait à les accorder sur tout le réseau.
 */
class NativePermissionResolverPolicyTest {

  private static final UUID SUBJECT = UUID.fromString("2a3b4c5d-6e7f-4081-9203-a4b5c6d7e8f0");
  /**
   * Identité hors espace Mojang, de la forme qu'un joueur Bedrock reçoit : les huit premiers octets
   * sont nuls et le XUID occupe les huit derniers, si bien que sa version d'UUID est 0 et non 4.
   * Elle est ici pour que le resolver soit tenu de rester agnostique de l'espace d'UUID — un
   * refactor qui déciderait quoi que ce soit à partir de la forme d'une identité doit rougir.
   */
  private static final UUID BEDROCK_SUBJECT = UUID.fromString("00000000-0000-0000-0009-1e4b7c2a5d63");
  private static final Map<String, String> CONTEXT = Map.of("network", "btc", "server", "proxy");

  private NativePermissionService service;
  private Player player;

  @BeforeEach
  void setUp() {
    service = mock(NativePermissionService.class);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(SUBJECT);
    when(service.context(player)).thenReturn(CONTEXT);
    when(service.load(any(UUID.class))).thenReturn(new CompletableFuture<>());
  }

  @Test
  void missingSnapshotRefusesAndStartsLoadingInsteadOfDelegating() {
    when(service.snapshot(SUBJECT)).thenReturn(null);
    final AtomicInteger delegateCalls = new AtomicInteger();
    final NativePermissionResolver resolver =
        new NativePermissionResolver(player, permission -> {
          delegateCalls.incrementAndGet();
          return Tristate.TRUE;
        }, service);

    final Tristate decision = resolver.getPermissionValue("btc.proxy.join");

    assertEquals(Tristate.FALSE, decision,
        "tant que le snapshot n'est pas chargé le proxy doit refuser : se replier sur Velocity"
            + " transformerait un délai d'entrée-sortie en autorisation");
    assertEquals(0, delegateCalls.get(),
        "le repli ne doit pas être consulté pendant le chargement, sinon la décision dépend de"
            + " l'ordre d'arrivée des entrées-sorties");
    verify(service).load(SUBJECT);
  }

  @Test
  void missingSnapshotYieldsAnEmptyMapConsistentWithItsRefusal() {
    when(service.snapshot(SUBJECT)).thenReturn(null);
    final NativePermissionResolver resolver =
        new NativePermissionResolver(player, permission -> Tristate.TRUE, service);

    final Map<String, Boolean> map = resolver.getPermissionMap();

    assertEquals(Map.of(), map,
        "la carte doit rester vide pendant le chargement : annoncer des permissions que"
            + " getPermissionValue refuse ferait diverger les deux surfaces du même resolver");
    verify(service).load(SUBJECT);
  }

  @Test
  void loadedSnapshotDecidesWithoutConsultingTheFallback() {
    when(service.snapshot(SUBJECT)).thenReturn(snapshotWith(node("btc.proxy.join", true)));
    final AtomicInteger delegateCalls = new AtomicInteger();
    final NativePermissionResolver resolver =
        new NativePermissionResolver(player, permission -> {
          delegateCalls.incrementAndGet();
          return Tristate.FALSE;
        }, service);

    assertEquals(Tristate.TRUE, resolver.getPermissionValue("btc.proxy.join"),
        "un allow natif doit être rendu tel quel");
    assertEquals(0, delegateCalls.get(),
        "une assignation native couvre le node : le repli n'a pas à être consulté");
  }

  @Test
  void explicitDenyCannotBeOverturnedByTheFallback() {
    when(service.snapshot(SUBJECT)).thenReturn(snapshotWith(node("btc.proxy.admin", false)));
    final PermissionFunction permissive = mock(PermissionFunction.class);
    when(permissive.getPermissionValue(any())).thenReturn(Tristate.TRUE);
    final NativePermissionResolver resolver =
        new NativePermissionResolver(player, permissive, service);

    assertEquals(Tristate.FALSE, resolver.getPermissionValue("btc.proxy.admin"),
        "un deny explicite est une décision, pas une absence de décision : aucune autre source ne"
            + " doit pouvoir la renverser");
    verify(permissive, never()).getPermissionValue(any());
  }

  @Test
  void uncoveredNodeIsTheOnlyCaseThatReachesTheFallback() {
    when(service.snapshot(SUBJECT)).thenReturn(snapshotWith(node("btc.proxy.join", true)));
    final AtomicInteger delegateCalls = new AtomicInteger();
    final NativePermissionResolver resolver =
        new NativePermissionResolver(player, permission -> {
          delegateCalls.incrementAndGet();
          return Tristate.TRUE;
        }, service);

    assertEquals(Tristate.TRUE, resolver.getPermissionValue("un.autre.plugin.node"),
        "un node qu'aucune assignation ne couvre revient à la source Velocity, qui reste seule"
            + " compétente pour les permissions des plugins du proxy");
    assertEquals(1, delegateCalls.get(), "le repli doit être consulté exactement une fois");
  }

  @Test
  void uncoveredNodeStaysUndefinedWhenThereIsNoFallbackAtAll() {
    when(service.snapshot(SUBJECT)).thenReturn(snapshotWith(node("btc.proxy.join", true)));
    final NativePermissionResolver resolver = new NativePermissionResolver(player, null, service);

    assertEquals(Tristate.UNDEFINED, resolver.getPermissionValue("un.autre.plugin.node"),
        "sans repli, un node non couvert est indéfini et non refusé : « personne n'en a décidé »"
            + " n'est pas « quelqu'un a décidé non », et Velocity traite déjà l'indéfini comme un"
            + " refus pour ses propres vérifications");
    assertTrue(resolver.getPermissionValue("btc.proxy.join").asBoolean(),
        "l'absence de repli ne change rien aux nodes réellement assignés");
  }

  @Test
  void aUuidOutsideTheMojangSpaceIsResolvedLikeAnyOther() {
    final Player bedrockPlayer = mock(Player.class);
    when(bedrockPlayer.getUniqueId()).thenReturn(BEDROCK_SUBJECT);
    when(service.context(bedrockPlayer)).thenReturn(CONTEXT);
    when(service.snapshot(BEDROCK_SUBJECT))
        .thenReturn(snapshotFor(BEDROCK_SUBJECT, node("btc.proxy.join", true)));
    final NativePermissionResolver resolver =
        new NativePermissionResolver(bedrockPlayer, null, service);

    assertTrue(resolver.getPermissionValue("btc.proxy.join").asBoolean(),
        "l'espace d'UUID n'est pas un critère d'autorisation : une identité Floodgate dont le"
            + " snapshot accorde un node l'obtient comme n'importe quelle autre");
    assertEquals(Tristate.UNDEFINED, resolver.getPermissionValue("un.autre.plugin.node"),
        "et un node non couvert reste indéfini pour elle aussi, sans traitement particulier");
  }

  @Test
  void aMissingSnapshotRefusesTheSameWayWhateverTheUuidSpace() {
    final Player bedrockPlayer = mock(Player.class);
    when(bedrockPlayer.getUniqueId()).thenReturn(BEDROCK_SUBJECT);
    when(service.snapshot(BEDROCK_SUBJECT)).thenReturn(null);
    final NativePermissionResolver resolver =
        new NativePermissionResolver(bedrockPlayer, permission -> Tristate.TRUE, service);

    assertEquals(Tristate.FALSE, resolver.getPermissionValue("btc.proxy.join"),
        "le refus pendant le chargement vaut pour toute identité : le traiter à part pour une"
            + " plateforme ferait de l'origine un critère de décision");
    verify(service).load(BEDROCK_SUBJECT);
    verify(service, never()).load(SUBJECT);
  }

  // --- fixtures ------------------------------------------------------------------------------

  private static NativePermissionSnapshot snapshotWith(final NativePermissionSnapshot.Node... nodes) {
    return snapshotFor(SUBJECT, nodes);
  }

  private static NativePermissionSnapshot snapshotFor(
      final UUID subject,
      final NativePermissionSnapshot.Node... nodes
  ) {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = subject;
    snapshot.permissions = List.of(nodes);
    snapshot.revision = 1L;
    return snapshot;
  }

  private static NativePermissionSnapshot.Node node(final String name, final boolean value) {
    final NativePermissionSnapshot.Node node = new NativePermissionSnapshot.Node();
    node.node = name;
    node.value = value;
    node.sourceId = "qa";
    return node;
  }
}
