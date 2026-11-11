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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

  private static final int EVENTS = 2;
  private static final int SECTIONS = 2;
  private static final int SEATS_PER_SECTION = 3;
  private static final int MIGRATIONS = 3;
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

  private static long number(Database database, String sql) throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
