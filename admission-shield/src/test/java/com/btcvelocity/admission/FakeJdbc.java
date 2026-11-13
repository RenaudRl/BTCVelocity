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

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * A database that records every statement and its bound values and answers queries from a queue:
 * enough to check what is asked and in what order, without a PostgreSQL at hand.
 */
final class FakeJdbc {

  /** One statement as run: its SQL and its bound values by index. */
  record Call(String sql, Map<Integer, Object> params) {
  }

  final List<Call> calls = new ArrayList<>();
  final List<String> transaction = new ArrayList<>();
  private final Deque<List<Object[]>> answers = new ArrayDeque<>();
  int updateCount = 1;

  /** Queues the rows of the next query. */
  FakeJdbc answer(final Object[]... rows) {
    this.answers.add(List.of(rows));
    return this;
  }

  DataSource dataSource() {
    return proxy(DataSource.class, (method, args) ->
        method.equals("getConnection") ? connection() : null);
  }

  private Connection connection() {
    return proxy(Connection.class, (method, args) -> switch (method) {
      case "prepareStatement" -> statement((String) args[0]);
      case "createStatement" -> statement(null);
      case "createArrayOf" -> proxy(Array.class, (inner, none) ->
          inner.equals("getArray") ? args[1] : null);
      case "commit", "rollback" -> {
        this.transaction.add(method);
        yield null;
      }
      case "setAutoCommit" -> {
        this.transaction.add("autoCommit=" + args[0]);
        yield null;
      }
      default -> null;
    });
  }

  private PreparedStatement statement(final String prepared) {
    final Map<Integer, Object> params = new TreeMap<>();
    return proxy(PreparedStatement.class, (method, args) -> {
      if (method.startsWith("set") && args != null && args.length == 2
          && args[0] instanceof Integer index) {
        params.put(index, args[1]);
        return null;
      }
      return switch (method) {
        case "execute" -> {
          this.calls.add(new Call((String) args[0], Map.of()));
          yield false;
        }
        case "executeUpdate" -> {
          this.calls.add(new Call(prepared, Map.copyOf(params)));
          yield this.updateCount;
        }
        case "executeQuery" -> {
          this.calls.add(new Call(prepared, Map.copyOf(params)));
          yield rows(this.answers.isEmpty() ? List.of() : this.answers.poll());
        }
        default -> null;
      };
    });
  }

  private static ResultSet rows(final List<Object[]> rows) {
    final int[] cursor = {-1};
    final boolean[] wasNull = {false};
    return proxy(ResultSet.class, (method, args) -> {
      final Object value = method.startsWith("get") && args[0] instanceof Integer column
          ? rows.get(cursor[0])[column - 1] : null;
      if (method.startsWith("get")) {
        wasNull[0] = value == null;
      }
      return switch (method) {
        case "next" -> ++cursor[0] < rows.size();
        case "wasNull" -> wasNull[0];
        case "getString" -> (String) value;
        // JDBC reads an SQL NULL as 0 and tells it by wasNull().
        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
        default -> null;
      };
    });
  }

  private interface Handler {
    Object handle(String method, Object[] args);
  }

  private static <T> T proxy(final Class<T> type, final Handler handler) {
    return type.cast(Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
        new Class<?>[] {type}, (self, method, args) -> {
          final Object answer = handler.handle(method.getName(), args);
          if (answer == null && method.getReturnType() == boolean.class) {
            return false;
          }
          if (answer == null && method.getReturnType() == int.class) {
            return 0;
          }
          return answer;
        }));
  }
}
