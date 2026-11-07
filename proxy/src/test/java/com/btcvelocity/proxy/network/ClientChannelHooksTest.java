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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.connection.ClientChannelHook;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
 * that fails must neither stop the others, nor refuse the connection, nor flood the log.
 */
class ClientChannelHooksTest {

  private static final String OWNER = "someplugin";
  private static final String SENTINEL = "end of test";

  @Test
  void hooksRunInRegistrationOrderOnTheChannel() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> calls = new ArrayList<>();
    final EmbeddedChannel channel = new EmbeddedChannel();
    hooks.add(OWNER, seen -> calls.add("first:" + (seen == channel)));
    hooks.add(OWNER, seen -> calls.add("second"));

    hooks.run(channel);

    assertEquals(List.of("first:true", "second"), calls);
  }

  @Test
  void aRemovedHookIsNotCalledAnymore() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> calls = new ArrayList<>();
    final ClientChannelHook removed = seen -> calls.add("removed");
    hooks.add(OWNER, removed);
    hooks.add(OWNER, seen -> calls.add("kept"));
    hooks.remove("otherplugin", removed); // not its owner: stays
    hooks.run(new EmbeddedChannel());
    hooks.remove(OWNER, removed);

    hooks.run(new EmbeddedChannel());

    assertEquals(List.of("removed", "kept", "kept"), calls);
  }

  @Test
  void theHandlersOfAFailingHookAreRemovedAndTheOthersKept() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    hooks.add(OWNER, channel -> channel.pipeline().addLast("kept", new ChannelInboundHandlerAdapter()));
    hooks.add(OWNER, channel -> {
      channel.pipeline().addFirst("half-a", new ChannelInboundHandlerAdapter());
      channel.pipeline().addLast("half-b", new ChannelInboundHandlerAdapter());
      throw new IllegalStateException("plugin bug after two handlers");
    });
    final EmbeddedChannel channel = new EmbeddedChannel();

    withWarnings(events -> hooks.run(channel));

    assertNull(channel.pipeline().get("half-a"));
    assertNull(channel.pipeline().get("half-b"));
    assertTrue(channel.pipeline().get("kept") != null, "a hook that worked keeps its handlers");
    assertTrue(channel.isOpen());
  }

  @Test
  void aFailingHookIsLoggedAndTheOthersStillRun() {
    final List<String> calls = new ArrayList<>();
    final EmbeddedChannel channel = new EmbeddedChannel();
    final List<LogEvent> events = withWarnings(captured -> {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      hooks.add(OWNER, seen -> {
        throw new IllegalStateException("plugin bug");
      });
      hooks.add(OWNER, seen -> {
        throw new NoSuchMethodError("built for another api");
      });
      hooks.add(OWNER, seen -> calls.add("after"));
      hooks.run(channel);
    });

    assertEquals(List.of("after"), calls);
    assertTrue(channel.isOpen(), "a failing plugin must not close the connection");
    assertEquals(2, events.size());
    assertEquals(Level.WARN, events.get(0).getLevel());
    assertEquals("plugin bug", events.get(0).getThrown().getMessage());
    assertTrue(events.get(0).getMessage().getFormattedMessage().contains(OWNER),
        "the log names the plugin");
    assertEquals(NoSuchMethodError.class, events.get(1).getThrown().getClass());
  }

  @Test
  void aHookThatFailsOnEveryChannelLogsItsStackOnceThenStaysQuiet() {
    final List<LogEvent> events = withWarnings(captured -> {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      hooks.add(OWNER, seen -> {
        throw new IllegalStateException("broken on every join");
      });
      for (int i = 0; i < 50; i++) {
        hooks.run(new EmbeddedChannel());
      }
    });

    assertEquals(1, events.size(), "one line per interval, the first with its stack");
    assertEquals("broken on every join", events.get(0).getThrown().getMessage());
  }

  @Test
  void anAssertionErrorIsolatesTheHookLikeAnyOtherFailure() {
    final List<String> calls = new ArrayList<>();
    final EmbeddedChannel channel = new EmbeddedChannel();
    final List<LogEvent> events = withWarnings(captured -> {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      hooks.add(OWNER, seen -> {
        seen.pipeline().addLast("half", new ChannelInboundHandlerAdapter());
        throw new AssertionError("plugin assert");
      });
      hooks.add(OWNER, seen -> calls.add("after"));
      hooks.run(channel);
    });

    assertEquals(List.of("after"), calls);
    assertTrue(channel.isOpen(), "an AssertionError must not close the connection");
    assertNull(channel.pipeline().get("half"));
    assertEquals(1, events.size());
    assertEquals(AssertionError.class, events.get(0).getThrown().getClass());
  }

  @Test
  void aVirtualMachineErrorIsNotSwallowed() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    hooks.add(OWNER, seen -> {
      throw new OutOfMemoryError("test");
    });

    assertThrows(OutOfMemoryError.class, () -> hooks.run(new EmbeddedChannel()));
  }

  @Test
  void aSlowHookIsReported() {
    final List<LogEvent> events = withWarnings(captured -> {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      hooks.add(OWNER, seen -> Thread.sleep(TimeUnit.NANOSECONDS.toMillis(
          ClientChannelHooks.SLOW_HOOK_NANOS) + 5));
      hooks.run(new EmbeddedChannel()); // first call: class loading, not measured
      hooks.run(new EmbeddedChannel());
      hooks.run(new EmbeddedChannel());
    });

    assertEquals(1, events.size(), "a slow hook is reported once per interval");
    assertTrue(events.get(0).getMessage().getFormattedMessage().contains("must not block"));
  }

  @Test
  void theFirstCallOfEachHookIsNotMeasured() {
    final List<LogEvent> events = withWarnings(captured -> {
      final ClientChannelHooks hooks = new ClientChannelHooks();
      final ClientChannelHook slow = seen -> Thread.sleep(TimeUnit.NANOSECONDS.toMillis(
          ClientChannelHooks.SLOW_HOOK_NANOS) + 5);
      hooks.add(OWNER, slow);
      hooks.add("otherplugin", slow);
      hooks.run(new EmbeddedChannel());
    });

    assertTrue(events.isEmpty(), "the first call of each registered hook loads its classes");
  }

  /**
   * Runs an action with the hooks' warnings captured, and returns them once the async loggers
   * delivered them all (a sentinel logged after the action closes the list).
   */
  private static List<LogEvent> withWarnings(final ThrowingConsumer<List<LogEvent>> action) {
    final List<LogEvent> events = new CopyOnWriteArrayList<>();
    final AbstractAppender appender = new AbstractAppender("hook-warnings", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(final LogEvent event) {
        events.add(event.toImmutable());
      }
    };
    appender.start();
    final String name = ClientChannelHooks.class.getName();
    final org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ClientChannelHooks.class);
    final Level previous = logger.getLevel();
    Configurator.setLevel(name, Level.WARN);
    logger.addAppender(appender);
    try {
      action.accept(events);
      logger.warn(SENTINEL);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.stream().noneMatch(ClientChannelHooksTest::isSentinel)
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(events.stream().anyMatch(ClientChannelHooksTest::isSentinel), "log delivered");
    } catch (final Exception e) {
      throw new AssertionError(e);
    } finally {
      logger.removeAppender(appender);
      appender.stop();
      Configurator.setLevel(name, previous);
    }
    return events.stream().filter(event -> !isSentinel(event)).toList();
  }

  private static boolean isSentinel(final LogEvent event) {
    return SENTINEL.equals(event.getMessage().getFormattedMessage());
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
  }
}
