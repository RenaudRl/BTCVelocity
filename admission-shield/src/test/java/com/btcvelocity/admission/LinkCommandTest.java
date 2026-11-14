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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The staff subcommands of the account links. */
class LinkCommandTest {

  private static final UUID SISTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  private static final UUID BROTHER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

  private final ManualClock clock = new ManualClock();
  private final MemoryLinkStore store = new MemoryLinkStore();
  private final RecordingLogger logger = new RecordingLogger();
  private final List<String> told = new ArrayList<>();
  private final List<String> staff = new ArrayList<>();
  private final Set<UUID> banned = new HashSet<>();
  private boolean sanctionsDown;
  private final CommandSource console = source(true);

  private CommandSource source(final boolean permitted) {
    return (CommandSource) Proxy.newProxyInstance(
        CommandSource.class.getClassLoader(), new Class<?>[] {CommandSource.class},
        (self, method, args) -> {
          if (method.getName().equals("sendMessage") && args[0] instanceof Component text) {
            this.told.add(PlainTextComponentSerializer.plainText().serialize(text));
          }
          return method.getReturnType() == boolean.class ? permitted : null;
        });
  }

  private LinkCommand command() {
    return command(this.store);
  }

  private LinkCommand command(final LinkStore over) {
    final AdmissionConfig config = AccountLinkRuleTest.config(AccountLinkRuleTest.ON
        .formatted("observe"));
    final AccountLinks links = new AccountLinks(new AdmissionEngine(config,
        address -> Optional.empty()), config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY),
        over, (accounts, now) -> {
          if (this.sanctionsDown) {
            throw new SQLException("sanctions unreachable");
          }
          final Map<UUID, BanLookup.ActiveBan> found = new HashMap<>();
          accounts.stream().filter(this.banned::contains)
              .forEach(account -> found.put(account, new BanLookup.ActiveBan(7L, now)));
          return found;
        }, Runnable::run, Runnable::run, this.clock, this.logger.logger());
    links.recordAccepted(Attempt.claimed("Sister", InetAddress.ofLiteral("198.51.100.7"))
        .authenticated("Sister", SISTER, false, null));
    return new LinkCommand(links, this.logger.logger(), this.clock,
        name -> name.equals("Brother") ? Optional.of(BROTHER) : Optional.empty(), this.staff::add);
  }

  @Test
  void withoutTheLinksPermissionNothingIsReadOrChanged() {
    command().execute(source(false), new String[] {"forget", SISTER.toString()}).join();

    assertEquals(1, this.store.rows.size());
    assertTrue(this.told.getFirst().contains("btc.admission.links"), this.told.toString());
  }

  @Test
  void theRequestIsLoggedBeforeTheDatabaseAnswers() {
    final MemoryLinkStore down = new MemoryLinkStore() {
      @Override
      public synchronized int forget(final UUID account) {
        throw new IllegalStateException("database down");
      }
    };

    command(down).execute(this.console, new String[] {"forget", SISTER.toString()}).join();

    assertTrue(this.logger.has("INFO", "console asks to erase what is kept about " + SISTER),
        this.logger.lines().toString());
  }

  @Test
  void aHouseholdAroundABannedAccountIsToldToTheStaff() {
    this.banned.add(BROTHER);

    command().execute(this.console, new String[] {"household", "add", SISTER.toString(),
        BROTHER.toString()}).join();

    assertEquals(1, this.staff.size(), this.staff.toString());
    assertTrue(this.staff.getFirst().contains(BROTHER.toString()), this.staff.getFirst());
    assertTrue(this.logger.has("WARN", "under a network ban"), this.logger.lines().toString());
  }

  @Test
  void aHouseholdWhoseBanCheckFailedSaysSoInsteadOfBlamingTheDatabase() {
    final LinkCommand command = command();
    this.sanctionsDown = true;

    command.execute(this.console, new String[] {"household", "add", SISTER.toString(),
        BROTHER.toString()}).join();

    assertEquals(this.store.households.get(SISTER), this.store.households.get(BROTHER));
    assertTrue(this.logger.has("INFO", "console declared one household, ban check failed"),
        this.logger.lines().toString());
    assertTrue(this.told.getFirst().contains("vérification des bans réseau a échoué"),
        this.told.toString());
    assertTrue(this.told.stream().noneMatch(text -> text.contains("ne répond pas")),
        this.told.toString());
    assertTrue(this.staff.isEmpty(), "nothing is known to tell");
  }

  @Test
  void forgetOnAnAccountUnderABanIsToldToTheStaffAndStillErases() {
    final LinkCommand command = command();
    this.banned.add(SISTER);

    command.execute(this.console, new String[] {"forget", SISTER.toString()}).join();

    assertTrue(this.store.rows.isEmpty(), "the erasure is not refused");
    assertEquals(1, this.staff.size(), this.staff.toString());
    assertTrue(this.staff.getFirst().contains(SISTER.toString()), this.staff.getFirst());
    assertTrue(this.logger.has("WARN", "under a network ban"), this.logger.lines().toString());
  }

  @Test
  void forgetErasesEvenWhenTheBanCheckFails() {
    final LinkCommand command = command();
    this.sanctionsDown = true;

    command.execute(this.console, new String[] {"forget", SISTER.toString()}).join();

    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.told.getFirst().startsWith("2 ligne(s) effacée(s)"), this.told.toString());
    assertTrue(this.staff.isEmpty());
  }

  @Test
  void householdTakesAnOnlineNameAndIsLogged() {
    command().execute(this.console, new String[] {"household", "add", SISTER.toString(),
        "Brother", "fratrie", "du", "foyer"}).join();

    assertEquals(this.store.households.get(SISTER), this.store.households.get(BROTHER));
    assertTrue(this.logger.has("INFO", "console declared one household"), this.logger.lines()
        .toString());
  }

  @Test
  void forgetErasesTheAccountAndIsLogged() {
    command().execute(this.console, new String[] {"forget", SISTER.toString()}).join();

    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.told.getFirst().startsWith("2 ligne(s) effacée(s)"), this.told.toString());
    assertTrue(this.logger.has("INFO", "console erased 2 row(s) about " + SISTER),
        this.logger.lines().toString());
  }

  @Test
  void anUnknownNameChangesNothing() {
    command().execute(this.console, new String[] {"forget", "Nobody"}).join();

    assertEquals(1, this.store.rows.size());
    assertTrue(this.told.getFirst().startsWith("Compte inconnu"), this.told.toString());
  }

  @Test
  void altsNameTheJavaAndTheBedrockAccountsOfOneAddress() throws SQLException {
    final UUID bedrock = new UUID(0L, 2535400000000001L);
    final InetAddress home = InetAddress.ofLiteral("198.51.100.7");
    for (UUID account : List.of(BROTHER, bedrock)) {
      this.store.record(LinkKeyTest.key(LinkKeyTest.TEST_KEY)
          .sighting(account, home, this.clock.instant()));
    }

    command().execute(this.console, new String[] {"alts", SISTER.toString()}).join();

    final String shown = String.join("\n", this.told);
    assertTrue(shown.contains(BROTHER + " [java]"), shown);
    assertTrue(shown.contains(bedrock + " [bedrock]"), shown);
  }

  @Test
  void linksOffSaySo() {
    new LinkCommand(null, this.logger.logger(), this.clock, name -> Optional.empty(),
        this.staff::add).execute(this.console, new String[] {"alts", SISTER.toString()}).join();

    assertTrue(this.told.getFirst().startsWith("Comptes liés : désactivés"), this.told.toString());
  }
}
