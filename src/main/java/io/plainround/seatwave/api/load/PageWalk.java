package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.SeatWaveApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pagination consistency test: walk every available seat of the demo event while fans hold
 * seats and the venue adds seats between pages, once with the API's cursors and once with an
 * offset, on two identical databases with the same seeded schedule of changes.
 *
 * <p>The truth is computed, not trusted: a seat available from the first page to the last must be
 * returned exactly once. Seats that changed during the walk may appear or not; neither counts.
 *
 * <p>Not thread-safe: one walk runs from one thread.
 */
public final class PageWalk {

  private static final Pattern SEAT = Pattern.compile("\"seat\":\"([A-T]-[0-9]{1,4})\"");
  private static final Pattern NEXT_CURSOR = Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_-]+)\"");
  private static final long RANDOM_SEED = 20_261_111L;
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final int CREATED = 201;
  // New seats get numbers after Module 1's 1 to 1,000 in their section.
  private static final int FIRST_NEW_SEAT_NUMBER = 1_001;

  private final ApiSettings settings;
  private final Changes changes;

  /**
   * Prepares a walk.
   *
   * @param settings the API's settings; the database must be in memory
   * @param changes the page size and how much changes between pages
   */
  public PageWalk(ApiSettings settings, Changes changes) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.changes = Objects.requireNonNull(changes, "changes");
  }

  /**
   * The walk's size and the changes made between pages.
   *
   * @param pageSize seats per page
   * @param holdsPerPage seats fans hold after each page
   * @param insertsPerPage seats the venue adds after each page
   */
  public record Changes(int pageSize, int holdsPerPage, int insertsPerPage) {

    /** Checks the fields. */
    public Changes {
      if (pageSize < 1 || holdsPerPage < 0 || insertsPerPage < 0) {
        throw new IllegalArgumentException("page size 1 or more, changes 0 or more");
      }
    }
  }

  /**
   * One walk's result.
   *
   * @param pages pages read
   * @param returned seats returned in all
   * @param stableSeats seats that were available for the whole walk
   * @param skipped stable seats never returned
   * @param repeated seats returned more than once
   */
  public record Walk(int pages, int returned, int stableSeats, int skipped, int repeated) {}

  /**
   * Both walks and the sizes of the cursor walk's pages.
   *
   * @param cursor the walk with the API's cursors
   * @param offset the walk with LIMIT and OFFSET
   * @param averagePageBytes the mean size of a cursor page's body
   * @param largestPageBytes the largest cursor page's body
   * @param pageLatency the latency of the cursor pages, through HTTP
   */
  public record Report(
      Walk cursor, Walk offset, long averagePageBytes, long largestPageBytes, String pageLatency) {}

  /**
   * Runs both walks.
   *
   * @return the report
   * @throws IOException if the port cannot be bound
   * @throws SQLException if a database fails
   * @throws InterruptedException if interrupted while waiting for a reply
   */
  public Report run() throws IOException, SQLException, InterruptedException {
    Latencies latencies = new Latencies();
    long[] bytes = new long[2];
    Walk cursorWalk;
    try (SeatWaveApi api = SeatWaveApi.start(withDatabase("cursor"), Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
      Tracker tracker = new Tracker(api, client, initialSeats());
      String cursor = "";
      int pages = 0;
      do {
        String query = cursor.isEmpty() ? "" : "&cursor=" + cursor;
        ApiClient.Answer page =
            client.get("/v1/events/1/seats?state=available&limit=" + changes.pageSize() + query);
        pages++;
        latencies.add(page.elapsed());
        long pageBytes = page.body().getBytes(StandardCharsets.UTF_8).length;
        bytes[0] += pageBytes;
        bytes[1] = Math.max(bytes[1], pageBytes);
        tracker.see(labelsIn(page.body()));
        tracker.change();
        Matcher next = NEXT_CURSOR.matcher(page.body());
        cursor = next.find() ? next.group(1) : "";
      } while (!cursor.isEmpty());
      cursorWalk = tracker.result(pages);
    }
    Walk offsetWalk;
    try (SeatWaveApi api = SeatWaveApi.start(withDatabase("offset"), Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
      Tracker tracker = new Tracker(api, client, initialSeats());
      OffsetSeatReader reader = new OffsetSeatReader(api.database(), Clock.systemUTC());
      int pages = 0;
      List<String> page;
      do {
        page = reader.availableSeats(changes.pageSize(), pages * changes.pageSize());
        pages++;
        tracker.see(page);
        tracker.change();
      } while (page.size() == changes.pageSize());
      offsetWalk = tracker.result(pages);
    }
    return new Report(
        cursorWalk, offsetWalk, bytes[0] / cursorWalk.pages(), bytes[1], latencies.summary());
  }

  private Set<String> initialSeats() {
    Set<String> seats = new HashSet<>();
    for (int section = 0; section < settings.seed().sections(); section++) {
      for (int number = 1; number <= settings.seed().seatsPerSection(); number++) {
        seats.add((char) ('A' + section) + "-" + number);
      }
    }
    return seats;
  }

  private ApiSettings withDatabase(String name) {
    return new ApiSettings(
        settings.httpPort(),
        settings.maxInFlight(),
        "jdbc:h2:mem:page-walk-" + name,
        settings.seed(),
        settings.payment(),
        settings.seatPrice(),
        settings.currency(),
        settings.retryAfter(),
        settings.version0());
  }

  private static List<String> labelsIn(String body) {
    return SEAT.matcher(body).results().map(match -> match.group(1)).toList();
  }

  /** Counts what a walk saw and makes the same changes, in the same order, for both walks. */
  private final class Tracker {
    private final SeatWaveApi api;
    private final ApiClient client;
    private final Set<String> stable;
    private final Map<String, Integer> timesSeen = new HashMap<>();
    private final SplittableRandom random = new SplittableRandom(RANDOM_SEED);
    private final Map<Character, Integer> nextNewSeat = new HashMap<>();
    private int returned;

    Tracker(SeatWaveApi api, ApiClient client, Set<String> initial) {
      this.api = api;
      this.client = client;
      this.stable = new HashSet<>(initial);
    }

    void see(List<String> labels) {
      returned += labels.size();
      labels.forEach(label -> timesSeen.merge(label, 1, Integer::sum));
    }

    void change() throws IOException, InterruptedException, SQLException {
      for (int hold = 0; hold < changes.holdsPerPage(); hold++) {
        String seat = randomSeat();
        ApiClient.Answer answer =
            client.postJson(
                "/v1/holds",
                "walk-" + seat,
                "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"walker\"}");
        if (answer.status() == CREATED) {
          // A seat held during the walk is not stable: the walk may or may not return it.
          stable.remove(seat);
        }
      }
      for (int insert = 0; insert < changes.insertsPerPage(); insert++) {
        insertSeat((char) ('A' + random.nextInt(settings.seed().sections())));
      }
    }

    Walk result(int pages) {
      int skipped = (int) stable.stream().filter(seat -> !timesSeen.containsKey(seat)).count();
      int repeated = (int) timesSeen.values().stream().filter(times -> times > 1).count();
      return new Walk(pages, returned, stable.size(), skipped, repeated);
    }

    private String randomSeat() {
      char section = (char) ('A' + random.nextInt(settings.seed().sections()));
      return section + "-" + (1 + random.nextInt(settings.seed().seatsPerSection()));
    }

    private void insertSeat(char section) throws SQLException {
      int number = nextNewSeat.merge(section, 1, Integer::sum) + FIRST_NEW_SEAT_NUMBER - 1;
      try (Connection connection = api.database().connect();
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO seats (event_id, seat_label, section, seat_number, status)"
                      + " VALUES (1, ?, ?, ?, 'AVAILABLE')")) {
        insert.setString(1, section + "-" + number);
        insert.setString(2, String.valueOf(section));
        insert.setInt(3, number);
        insert.executeUpdate();
      }
    }
  }
}
