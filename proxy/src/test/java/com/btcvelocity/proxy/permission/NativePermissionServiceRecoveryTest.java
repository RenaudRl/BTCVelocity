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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Récupérabilité de l'initialisation du backend proxy (PERM-025) et survie du catalogue de groupes
 * à une lecture impossible (PERM-026).
 *
 * <p>Ces deux comportements ne sont pas des détails d'implémentation. Le proxy est la première
 * surface qu'un joueur traverse, et un backend qui reste définitivement en panne après un incident
 * transitoire de base prive tout le réseau de ses permissions sans qu'aucune erreur ne survienne à
 * l'endroit où la décision est prise : le proxy répond « non défini », ce qui est indistinguable
 * d'un joueur réellement sans droits. De même, un catalogue de groupes remplacé par du vide après
 * un échec de lecture retire silencieusement toutes les permissions héritées.
 *
 * <p>La base est réelle : un pool Hikari, sa validation à la construction et un chemin de
 * configuration relu à chaque tentative ne se simulent pas honnêtement. Sans {@code BTC_PERM_PG_URL}
 * les tests sont ignorés plutôt que rouges.
 */
class NativePermissionServiceRecoveryTest {

  private static final String PG_URL = System.getenv("BTC_PERM_PG_URL");
  private static final String PG_USER = System.getenv("BTC_PERM_PG_USER");
  private static final String PG_PASSWORD =
      System.getenv().getOrDefault("BTC_PERM_PG_PASSWORD", "btc");

  /** Port réservé comme inutilisable : la connexion est refusée franchement, pas suspendue. */
  private static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/absent";

  private static final UUID SUBJECT = UUID.fromString("6b1c2d3e-4f50-4a61-8b72-9c8d7e6f5a40");

  @TempDir
  Path directory;

  private String tablePrefix;
  private Path configPath;
  private String previousConfigProperty;

  @BeforeEach
  void setUp() throws SQLException, IOException {
    assumeTrue(PG_URL != null && PG_USER != null,
        "BTC_PERM_PG_URL/BTC_PERM_PG_USER absents : campagne PostgreSQL réelle ignorée");
    tablePrefix =
        "perm_qa_proxy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
    createSchema();
    configPath = directory.resolve("btc-permissions.properties");
    previousConfigProperty = System.getProperty("btc.permissions.config");
    System.setProperty("btc.permissions.config", configPath.toString());
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (PG_URL == null || PG_USER == null) {
      return;
    }
    if (previousConfigProperty == null) {
      System.clearProperty("btc.permissions.config");
    } else {
      System.setProperty("btc.permissions.config", previousConfigProperty);
    }
    dropSchema();
  }

  @Test
  void anInitializationThatFailedOnceRetriesAndRecovers() throws Exception {
    writeConfig(UNREACHABLE_URL);
    try (NativePermissionService service = new NativePermissionService()) {
      assertNull(service.load(SUBJECT).join(),
          "une base injoignable ne peut produire aucun snapshot");

      writeConfig(PG_URL);

      final NativePermissionSnapshot recovered = service.load(SUBJECT).join();
      assertNotNull(recovered,
          "une panne transitoire de base ne doit pas condamner le backend : la tentative suivante"
              + " doit relire la configuration et rétablir le pool");
      assertEquals(SUBJECT, recovered.subject);
      assertEquals(7L, recovered.revision);
      assertTrue(
          recovered.permissions.stream().anyMatch(node -> "btc.proxy.join".equals(node.node)),
          "le snapshot rétabli doit porter ses nodes réels");
    }
  }

  @Test
  void anUnavailableBackendSaysSoInsteadOfLookingEmpty() throws Exception {
    writeConfig(UNREACHABLE_URL);
    try (NativePermissionService service = new NativePermissionService()) {
      service.load(SUBJECT).join();

      final NativePermissionHealth broken = service.health();
      assertFalse(broken.backendAvailable(),
          "un backend dont l'initialisation a échoué doit se déclarer indisponible, sans quoi"
              + " l'opérateur ne peut pas distinguer « aucune permission » de « backend en panne »");
      assertNotNull(broken.lastFailureReason(), "le motif du dernier échec doit rester lisible");
      assertTrue(
          broken.lastFailureReason().indexOf('@') < 0
              && !broken.lastFailureReason().contains("jdbc:"),
          "le motif ne doit porter aucune chaîne de connexion, obtenu : " + broken.lastFailureReason());

      writeConfig(PG_URL);
      service.load(SUBJECT).join();

      assertTrue(service.health().backendAvailable(),
          "après rétablissement, le backend ne doit plus se déclarer indisponible");
    }
  }

  @Test
  void aGroupCatalogSurvivesAFailedRefreshAndSaysItIsStale() throws Exception {
    writeConfig(PG_URL);
    try (NativePermissionService service = new NativePermissionService()) {
      assertNotNull(service.load(SUBJECT).join(), "l'amorçage doit réussir avec la base réelle");
      assertEquals(1, service.health().groupCatalogSize(),
          "le catalogue amorcé porte le groupe semé");
      assertFalse(service.health().groupCatalogStale(),
          "un catalogue fraîchement lu n'est pas périmé");

      dropGroupsTable();
      service.refreshGroupCatalog().join();

      assertEquals(1, service.health().groupCatalogSize(),
          "un rafraîchissement impossible doit conserver le dernier catalogue valide : le remplacer"
              + " par un catalogue vide transforme un incident de lecture en retrait silencieux de"
              + " toutes les permissions de groupe");
      assertTrue(service.health().groupCatalogStale(),
          "le catalogue conservé est périmé et doit se déclarer tel, sans quoi rien ne distingue un"
              + " catalogue à jour d'un catalogue figé depuis une panne");
      assertTrue(service.health().groupCatalogStaleSinceEpochMillis() > 0L,
          "la péremption doit être datée : une minute et une journée ne s'arbitrent pas pareil");
    }
  }

  // --- banc ----------------------------------------------------------------------------------

  private void writeConfig(final String jdbcUrl) throws IOException {
    final Properties properties = new Properties();
    properties.setProperty("jdbc-url", jdbcUrl);
    properties.setProperty("username", PG_USER);
    properties.setProperty("password", PG_PASSWORD);
    properties.setProperty("network-id", "btc-qa");
    properties.setProperty("server-id", "proxy-qa");
    properties.setProperty("table-prefix", tablePrefix);
    final StringBuilder text = new StringBuilder();
    properties.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    Files.writeString(configPath, text.toString(), StandardCharsets.UTF_8);
  }

  private void createSchema() throws SQLException {
    try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + tablePrefix + "players ("
          + "player_uuid TEXT NOT NULL, profile_id TEXT, revision BIGINT NOT NULL,"
          + " payload TEXT NOT NULL)");
      statement.execute("CREATE TABLE " + tablePrefix + "groups ("
          + "group_name TEXT PRIMARY KEY, payload TEXT NOT NULL)");
      statement.execute("INSERT INTO " + tablePrefix + "players"
          + " (player_uuid, profile_id, revision, payload) VALUES ('" + SUBJECT + "', NULL, 7, '"
          + "{\"revision\":7,\"permissions\":[{\"node\":\"btc.proxy.join\",\"value\":true,"
          + "\"sourceId\":\"qa\"}],\"directGroups\":[\"staff\"]}')");
      statement.execute("INSERT INTO " + tablePrefix
          + "groups (group_name, payload) VALUES ('staff', '"
          + "{\"name\":\"staff\",\"weight\":10,\"permissions\":[{\"node\":\"btc.proxy.staff\","
          + "\"value\":true,\"sourceId\":\"qa\"}]}')");
    }
  }

  private void dropGroupsTable() throws SQLException {
    try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE " + tablePrefix + "groups");
    }
  }

  private void dropSchema() throws SQLException {
    if (tablePrefix == null) {
      return;
    }
    try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + tablePrefix + "players");
      statement.execute("DROP TABLE IF EXISTS " + tablePrefix + "groups");
    }
  }
}
