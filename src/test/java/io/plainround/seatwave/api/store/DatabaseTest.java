package io.plainround.seatwave.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

  private static final int EVENTS = 2;
  private static final int SECTIONS = 2;
  private static final int SEATS_PER_SECTION = 3;
  private static final int MIGRATIONS = 4;
  private static final int LAST_SEAT_OF_A_SECTION = 1_000;

  @Test
  void appliesEachMigrationOnceAndSeedsOnce(@TempDir Path folder) throws Exception {
    String url = "jdbc:h2:file:" + folder.resolve("seatwave").toAbsolutePath();
    ApiSettings.Seed seed = new ApiSettings.Seed(EVENTS, SECTIONS, SEATS_PER_SECTION);
    Clock clock = Clock.fixed(TestApi.START, ZoneOffset.UTC);
    try (Database first = Database.open(url)) {
      first.seedIfEmpty(seed, clock);
    }

    try (Database again = Database.open(url)) {
      again.seedIfEmpty(seed, clock);

      assertThat(number(again, "SELECT COUNT(*) FROM schema_history")).isEqualTo(MIGRATIONS);
      assertThat(number(again, "SELECT COUNT(*) FROM events")).isEqualTo(EVENTS);
      assertThat(number(again, "SELECT COUNT(*) FROM seats"))
          .isEqualTo(SECTIONS * SEATS_PER_SECTION);
    }
  }

  @Test
  void fillsTheSeatNumberFromTheLabelForSeatsThatExistedBeforeIt() throws Exception {
    // V3 must migrate Module 1's rows, which had no seat_number: insert one as V2 left it.
    try (Database database = Database.open("jdbc:h2:mem:migration-" + System.nanoTime())) {
      try (Connection connection = database.connect();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM schema_history WHERE version = 3");
        statement.execute("DROP INDEX seats_in_map_order");
        statement.execute("DROP INDEX events_in_start_order");
        statement.execute("ALTER TABLE seats DROP COLUMN seat_number");
        statement.execute(
            "INSERT INTO seats (event_id, seat_label, section, status) VALUES (1, 'T-1000', 'T', 'AVAILABLE')");
        statement.execute(
            "RUNSCRIPT FROM 'classpath:/db/migration/V3__seat_order_for_cursors.sql'");
      }

      assertThat(number(database, "SELECT seat_number FROM seats WHERE seat_label = 'T-1000'"))
          .isEqualTo(LAST_SEAT_OF_A_SECTION);
    }
  }

  @Test
  void startsAfterACrashBetweenEveryScriptAndItsHistoryRow(@TempDir Path folder) throws Exception {
    String url = "jdbc:h2:file:" + folder.resolve("seatwave").toAbsolutePath();
    ApiSettings.Seed seed = new ApiSettings.Seed(EVENTS, SECTIONS, SEATS_PER_SECTION);
    try (Database first = Database.open(url)) {
      first.seedIfEmpty(seed, Clock.fixed(TestApi.START, ZoneOffset.UTC));
      // H2 commits each DDL statement at once, so a crash after a script and before its INSERT
      // leaves the schema changed and the history without the row: this is that state.
      try (Connection connection = first.connect();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM schema_history");
      }
    }

    try (Database again = Database.open(url)) {
      assertThat(number(again, "SELECT COUNT(*) FROM schema_history")).isEqualTo(MIGRATIONS);
      assertThat(number(again, "SELECT COUNT(*) FROM seats"))
          .isEqualTo(SECTIONS * SEATS_PER_SECTION);
    }
  }

  @Test
  void findsASeatByItsHoldThroughAnIndexNotATableScan() throws Exception {
    // The WHERE clauses of SeatLedger's findHold, leaseForConfirm, markConfirmed, cancel and
    // releaseLease, each read as H2 would run it.
    List<String> lookups =
        List.of(
            "SELECT event_id FROM seats WHERE hold_id = 'h'",
            "UPDATE seats SET status = 'CONFIRMING' WHERE hold_id = 'h' AND ((status = 'HELD'"
                + " AND hold_expires_at > 0) OR (status = 'CONFIRMING' AND confirm_lease_until <= 0))",
            "UPDATE seats SET status = 'CONFIRMED' WHERE hold_id = 'h' AND status = 'CONFIRMING'",
            "UPDATE seats SET status = 'AVAILABLE' WHERE hold_id = 'h' AND status = 'HELD'",
            "UPDATE seats SET confirm_lease_until = 1 WHERE hold_id = 'h' AND status = 'CONFIRMING'");
    try (Database database = Database.open("jdbc:h2:mem:plan-" + System.nanoTime())) {
      database.seedIfEmpty(
          new ApiSettings.Seed(EVENTS, SECTIONS, SEATS_PER_SECTION),
          Clock.fixed(TestApi.START, ZoneOffset.UTC));
      for (String lookup : lookups) {
        assertThat(plan(database, lookup))
            .as(lookup)
            .contains("SEATS_BY_HOLD")
            .doesNotContain("tableScan");
      }
    }
  }

  @Test
  void refusesAnUnknownSeatStatus() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:status-" + System.nanoTime());
        Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() ->
              statement.execute(
                  "INSERT INTO seats (event_id, seat_label, section, seat_number, status)"
                      + " VALUES (1, 'A-1', 'A', 1, 'SOLD_TWICE')"))
          .isInstanceOf(SQLException.class);
    }
  }

  private static String plan(Database database, String sql) throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static long number(Database database, String sql) throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
