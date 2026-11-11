package io.plainround.seatwave.api;

import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.http.ApiServer;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentClient;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One running SeatWave API: the database, the services, the HTTP server and the key purge, wired
 * once and closed together. The command line, the tests and the load tools all start it this way.
 *
 * <p>Thread-safe: after {@link #start} nothing in it changes.
 */
public final class SeatWaveApi implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveApi.class);
  // Expired keys are deleted once a minute, at most this many per run, so the purge never holds
  // locks on a large part of the table; at 24 hours of retention a minute's delay is noise.
  private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
  private static final int PURGE_BATCH = 10_000;

  private final Database database;
  private final ApiServer server;
  private final ScheduledExecutorService purge;

  private SeatWaveApi(Database database, ApiServer server, ScheduledExecutorService purge) {
    this.database = database;
    this.server = server;
    this.purge = purge;
  }

  /**
   * Opens the database (migrating and seeding it if needed) and starts serving.
   *
   * @param settings the settings
   * @param clock the clock for every expiry; tests pass one they can move
   * @return the running API
   * @throws SQLException if the database cannot be opened, migrated or seeded
   * @throws IOException if the port cannot be bound
   */
  public static SeatWaveApi start(ApiSettings settings, Clock clock)
      throws SQLException, IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    Database database = Database.open(settings.databaseUrl());
    try {
      database.seedIfEmpty(settings.seed(), clock);
      JsonCodec json = new JsonCodec();
      IdempotencyStore keys = new IdempotencyStore();
      ConfirmService confirms =
          new ConfirmService(
              database,
              keys,
              new PaymentClient(settings.payment(), json),
              new ConfirmService.Price(settings.seatPrice(), settings.currency()),
              settings.payment().lease(),
              clock);
      ApiServer server =
          ApiServer.start(
              settings,
              new ApiServer.Services(
                  database,
                  keys,
                  new CatalogService(database, clock),
                  new HoldService(database, keys, clock),
                  confirms,
                  json));
      ScheduledExecutorService purge = Executors.newSingleThreadScheduledExecutor();
      purge.scheduleWithFixedDelay(
          () -> purgeExpiredKeys(database, keys, clock),
          PURGE_INTERVAL.toMillis(),
          PURGE_INTERVAL.toMillis(),
          TimeUnit.MILLISECONDS);
      return new SeatWaveApi(database, server, purge);
    } catch (SQLException | IOException | RuntimeException startFailure) {
      database.close();
      throw startFailure;
    }
  }

  /**
   * The port the API listens on, on 127.0.0.1.
   *
   * @return the port
   */
  public int port() {
    return server.port();
  }

  /**
   * The database, for tools and tests that read the counts that prove the invariants.
   *
   * @return the database
   */
  public Database database() {
    return database;
  }

  @Override
  public void close() throws SQLException {
    purge.close();
    server.close();
    database.close();
  }

  private static void purgeExpiredKeys(Database database, IdempotencyStore keys, Clock clock) {
    try (Connection connection = database.connect()) {
      int deleted = keys.purgeExpired(connection, clock.instant(), PURGE_BATCH);
      LOG.info("purged {} expired idempotency keys", deleted);
    } catch (SQLException purgeFailure) {
      // A failed purge only delays deletion; the next run tries again. It must not kill the
      // scheduler, which a thrown exception would.
      LOG.warn("key purge failed, SQL state {}", purgeFailure.getSQLState(), purgeFailure);
    }
  }
}
