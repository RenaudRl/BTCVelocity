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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
  private final CommandSource console = (CommandSource) Proxy.newProxyInstance(
      CommandSource.class.getClassLoader(), new Class<?>[] {CommandSource.class},
      (self, method, args) -> {
        if (method.getName().equals("sendMessage") && args[0] instanceof Component text) {
          this.told.add(PlainTextComponentSerializer.plainText().serialize(text));
        }
        return method.getReturnType() == boolean.class ? true : null;
      });

  private LinkCommand command() {
    final AdmissionConfig config = AccountLinkRuleTest.config(AccountLinkRuleTest.ON
        .formatted("observe"));
    final AccountLinks links = new AccountLinks(new AdmissionEngine(config,
        address -> Optional.empty()), config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY),
        this.store, (accounts, now) -> java.util.Map.of(), Runnable::run, this.clock,
        this.logger.logger());
    links.recordAccepted(Attempt.claimed("Sister", InetAddress.ofLiteral("198.51.100.7"))
        .authenticated("Sister", SISTER, false, null));
    return new LinkCommand(links, this.logger.logger(), this.clock,
        name -> name.equals("Brother") ? Optional.of(BROTHER) : Optional.empty());
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
    assertTrue(this.told.getFirst().startsWith("1 ligne(s) effacée(s)"), this.told.toString());
    assertTrue(this.logger.has("INFO", "console erased what is kept about " + SISTER),
        this.logger.lines().toString());
  }

  @Test
  void anUnknownNameChangesNothing() {
    command().execute(this.console, new String[] {"forget", "Nobody"}).join();

    assertEquals(1, this.store.rows.size());
    assertTrue(this.told.getFirst().startsWith("Compte inconnu"), this.told.toString());
  }

  @Test
  void linksOffSaySo() {
    new LinkCommand(null, this.logger.logger(), this.clock, name -> Optional.empty())
        .execute(this.console, new String[] {"alts", SISTER.toString()}).join();

    assertTrue(this.told.getFirst().startsWith("Comptes liés : désactivés"), this.told.toString());
  }
}
