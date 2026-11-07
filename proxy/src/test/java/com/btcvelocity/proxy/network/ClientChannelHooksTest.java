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

package com.btcvelocity.proxy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.connection.ClientChannelHook;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

/**
 * Plugins hook the pipeline of every client channel: they run in registration order, and a plugin
 * that fails must neither stop the others nor refuse the connection.
 */
class ClientChannelHooksTest {

  @Test
  void hooksRunInRegistrationOrderOnTheChannel() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> calls = new ArrayList<>();
    final EmbeddedChannel channel = new EmbeddedChannel();
    hooks.add(seen -> calls.add("first:" + (seen == channel)));
    hooks.add(seen -> calls.add("second"));

    hooks.run(channel);

    assertEquals(List.of("first:true", "second"), calls);
  }

  @Test
  void aRemovedHookIsNotCalledAnymore() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> calls = new ArrayList<>();
    final ClientChannelHook removed = seen -> calls.add("removed");
    hooks.add(removed);
    hooks.add(seen -> calls.add("kept"));
    hooks.remove(removed);

    hooks.run(new EmbeddedChannel());

    assertEquals(List.of("kept"), calls);
  }

  @Test
  void aFailingHookIsLoggedAndTheOthersStillRun() throws InterruptedException {
    final List<LogEvent> events = new CopyOnWriteArrayList<>();
    final AbstractAppender appender = new AbstractAppender("hook-failures", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(final LogEvent event) {
        events.add(event.toImmutable());
      }
    };
    appender.start();
    Configurator.setLevel(ClientChannelHooks.class.getName(), Level.WARN);
    final org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ClientChannelHooks.class);
    logger.addAppender(appender);
    try {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      final List<String> calls = new ArrayList<>();
      hooks.add(seen -> {
        throw new IllegalStateException("plugin bug");
      });
      hooks.add(seen -> {
        throw new NoSuchMethodError("built for another api");
      });
      hooks.add(seen -> calls.add("after"));
      final EmbeddedChannel channel = new EmbeddedChannel();

      hooks.run(channel);

      assertEquals(List.of("after"), calls);
      assertTrue(channel.isOpen(), "a failing plugin must not close the connection");
      // the proxy logs through async loggers: wait, bounded, for the events to be delivered
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.size() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(2, events.size());
      assertEquals(Level.WARN, events.get(0).getLevel());
      assertEquals("plugin bug", events.get(0).getThrown().getMessage());
      assertEquals(NoSuchMethodError.class, events.get(1).getThrown().getClass());
    } finally {
      logger.removeAppender(appender);
      appender.stop();
    }
  }
}
