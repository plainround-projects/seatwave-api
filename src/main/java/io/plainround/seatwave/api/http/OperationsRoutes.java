package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/**
 * GET /health and GET /metrics, for the people and tools that run SeatWave.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class OperationsRoutes {

  private static final int OK = 200;

  private final Database database;
  private final IdempotencyStore keys;
  private final ApiMetrics metrics;
  private final JsonCodec json;

  OperationsRoutes(Database database, IdempotencyStore keys, ApiMetrics metrics, JsonCodec json) {
    this.database = Objects.requireNonNull(database, "database");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
  }

  Reply health(ApiRequest request) throws SQLException {
    try (Connection connection = database.connect()) {
      // "Up" means the database answers, not only that the process runs: a check that never
      // touches the database stays green while every booking fails.
      keys.count(connection);
    }
    return Reply.json(OK, json.write(new V1Views.HealthView("up")));
  }

  Reply metrics(ApiRequest request) throws SQLException {
    long stored;
    try (Connection connection = database.connect()) {
      stored = keys.count(connection);
    }
    return new Reply(OK, "text/plain; version=0.0.4", metrics.render(stored), Map.of());
  }
}
