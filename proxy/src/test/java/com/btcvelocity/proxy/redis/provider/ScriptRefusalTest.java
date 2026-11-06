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

package com.btcvelocity.proxy.redis.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisLoadingException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ScriptRefusalTest {

  @Test
  void anErrorReplyIsARefusalEvenInsideAFuture() {
    final RedisCommandExecutionException noPerm = new RedisCommandExecutionException(
        "NOPERM User noscript has no permissions to run the 'eval' command");
    assertTrue(LettuceProvider.isScriptRefusal(noPerm));
    assertTrue(LettuceProvider.isScriptRefusal(new CompletionException(noPerm)));
    assertTrue(LettuceProvider.isScriptRefusal(
        new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'")));
  }

  @Test
  void theNetworkAndPassingServerStatesAreNot() {
    assertFalse(LettuceProvider.isScriptRefusal(new RedisCommandTimeoutException("timed out")));
    assertFalse(LettuceProvider.isScriptRefusal(new RedisException("Currently not connected")));
    assertFalse(LettuceProvider.isScriptRefusal(new RedisLoadingException("LOADING")));
    assertFalse(LettuceProvider.isScriptRefusal(new RedisBusyException("BUSY")));
    assertFalse(LettuceProvider.isScriptRefusal(new CompletionException(new TimeoutException())));
  }
}
