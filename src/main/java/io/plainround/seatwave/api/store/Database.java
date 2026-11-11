package io.plainround.seatwave.api.store;

import io.plainround.seatwave.api.config.ApiSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The embedded H2 database: opened once, migrated once, then one short-lived connection per unit of
 * work. Opening a connection to an embedded H2 database that is already open costs microseconds, so
 * no pool is needed; the number of connections in use is bounded by the HTTP layer's limit on
 * requests in flight.
 *
 * <p>Thread-safe: {@link #connect()} may be called from any thread; each connection belongs to the
 * thread that opened it until it is closed.
 */
public final class Database implements AutoCloseable {

  // Applied in this order, each once. A new migration is appended, never edited after release.
  private static final List<String> MIGRATIONS =
      List.of(
          "V1__module_1_schema.sql",
          "V2__idempotency_and_payment.sql",
          "V3__seat_order_for_cursors.sql");

  private final String url;
  // An in-memory H2 database lives while one connection to it is open; this one keeps it alive.
  private final Connection anchor;

  private Database(String url, Connection anchor) {
    this.url = url;
    this.anchor = anchor;
  }

  /**
   * Opens the database and applies every migration it has not seen.
   *
   * @param url the H2 JDBC URL
   * @return the open database
   * @throws SQLException if the database cannot be opened or a migration fails
   */
  public static Database open(String url) throws SQLException {
    Objects.requireNonNull(url, "url");
    Connection anchor = DriverManager.getConnection(url);
    try {
      migrate(anchor);
      return new Database(url, anchor);
    } catch (SQLException migrationFailure) {
      anchor.close();
      throw migrationFailure;
    }
  }

  /**
   * Opens a connection in auto-commit mode; the caller closes it.
   *
   * @return a new connection
   * @throws SQLException if the database is unreachable
   */
  public Connection connect() throws SQLException {
    return DriverManager.getConnection(url);
  }

  /**
   * Fills an empty database with the demo events and seats; does nothing if any event exists.
   *
   * @param seed how much to create
   * @param clock the clock the event dates are counted from
   * @throws SQLException if the seed cannot be written
   */
  public void seedIfEmpty(ApiSettings.Seed seed, Clock clock) throws SQLException {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(clock, "clock");
    try (Connection connection = connect()) {
      if (!hasEvents(connection)) {
        DemoSeed.write(connection, seed, clock.instant());
      }
    }
  }

  @Override
  public void close() throws SQLException {
    anchor.close();
  }

  private static boolean hasEvents(Connection connection) throws SQLException {
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM events")) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }

  private static void migrate(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS schema_history (version INT PRIMARY KEY,"
              + " script VARCHAR(80) NOT NULL)");
    }
    for (int index = 0; index < MIGRATIONS.size(); index++) {
      int version = index + 1;
      if (isApplied(connection, version)) {
        continue;
      }
      try (Statement statement = connection.createStatement()) {
        // The script name is one of the constants above, never input, so this is not injectable.
        statement.execute("RUNSCRIPT FROM 'classpath:/db/migration/" + MIGRATIONS.get(index) + "'");
      }
      try (PreparedStatement record =
          connection.prepareStatement(
              "INSERT INTO schema_history (version, script) VALUES (?, ?)")) {
        record.setInt(1, version);
        record.setString(2, MIGRATIONS.get(index));
        record.executeUpdate();
      }
    }
  }

  private static boolean isApplied(Connection connection, int version) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?")) {
      query.setInt(1, version);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
}
