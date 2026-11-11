package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.SeatWaveApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentStandIn;
import io.plainround.seatwave.api.payment.StandInMode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The retry storm: every write sent many times at once, through real HTTP on 127.0.0.1, then the
 * proof read from the database and from the payment provider's ledger, never from the replies.
 *
 * <p>Three scenarios: one hold sent {@code concurrency} times at once with one key; {@code pairs}
 * holds and their confirms, each sent twice at once with its own key; and one seat wanted by {@code
 * concurrency} fans with different keys (Module 1's race, as a control). A client that gets 503
 * retries the same request with the same key, with exponential backoff and jitter, as the contract
 * asks.
 *
 * <p>Not thread-safe: one storm runs once, from one thread; it starts its own virtual threads.
 */
public final class RetryStorm {

  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final int CREATED = 201;
  private static final int UNAVAILABLE = 503;
  private static final int CONFLICT = 409;
  // Retries after a 503: at most 6, starting at 20 ms and doubling, capped at 640 ms, with full
  // jitter (a random wait from 0 to the cap) so the retries do not arrive together again.
  private static final int MAX_RETRIES = 6;
  private static final Duration FIRST_BACKOFF = Duration.ofMillis(20);
  private static final Duration MAX_BACKOFF = Duration.ofMillis(640);
  private static final long RANDOM_SEED = 20_261_111L;
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final char FIRST_PAIR_SECTION = 'C';
  // Sections A and B belong to scenarios 1 and 3.
  private static final int SECTIONS_NOT_FOR_PAIRS = 2;

  private final ApiSettings settings;
  private final int concurrency;
  private final int pairs;
  private final SplittableRandom random = new SplittableRandom(RANDOM_SEED);
  private final Latencies latencies = new Latencies();
  private final Map<Integer, Integer> statusCounts = new ConcurrentHashMap<>();
  private int retries;

  /**
   * Prepares a storm.
   *
   * @param settings the API's settings; the database must be in memory and the provider's port free
   * @param concurrency how many copies of one request are sent at once
   * @param pairs how many seats are held and confirmed, each request twice
   */
  public RetryStorm(ApiSettings settings, int concurrency, int pairs) {
    this.settings = Objects.requireNonNull(settings, "settings");
    if (concurrency < 1 || pairs < 1) {
      throw new IllegalArgumentException("concurrency and pairs must be 1 or more");
    }
    int seatsForPairs =
        (settings.seed().sections() - SECTIONS_NOT_FOR_PAIRS) * settings.seed().seatsPerSection();
    if (pairs > seatsForPairs) {
      throw new IllegalArgumentException("pairs must fit in sections C onward: " + seatsForPairs);
    }
    this.concurrency = concurrency;
    this.pairs = pairs;
  }

  /**
   * What the storm sent and what the database and the provider hold afterwards.
   *
   * @param sameKeyReplies scenario 1: status code to count
   * @param sameKeyHoldIds scenario 1: distinct hold ids in the replies
   * @param sameKeySeatsHeld scenario 1: seats the database shows held under any of those ids
   * @param pairsRequests scenario 2: requests sent (holds and confirms, each twice)
   * @param pairsBookings scenario 2: rows in {@code bookings}
   * @param seatsBookedTwice scenario 2: seats with more than one booking row
   * @param providerCharges scenario 2: charges in the provider's ledger
   * @param holdsChargedTwice scenario 2: holds with more than one charge
   * @param sameSeatReplies scenario 3: status code to count
   * @param sameSeatHolders scenario 3: rows holding the seat
   * @param keysStored rows in the idempotency-key table at the end
   * @param retries how many times a client retried after a 503 or a confirm-in-progress 409
   * @param allReplies every final status code, to count
   * @param latency the latency of every request, retries included
   */
  public record Report(
      Map<Integer, Integer> sameKeyReplies,
      int sameKeyHoldIds,
      long sameKeySeatsHeld,
      int pairsRequests,
      long pairsBookings,
      long seatsBookedTwice,
      int providerCharges,
      long holdsChargedTwice,
      Map<Integer, Integer> sameSeatReplies,
      long sameSeatHolders,
      long keysStored,
      int retries,
      Map<Integer, Integer> allReplies,
      String latency) {}

  /**
   * Runs the three scenarios.
   *
   * @return the report
   * @throws IOException if a port cannot be bound
   * @throws SQLException if the database fails
   * @throws InterruptedException if interrupted while waiting for the requests
   */
  public Report run() throws IOException, SQLException, InterruptedException {
    try (PaymentStandIn provider =
            PaymentStandIn.start(
                settings.payment().baseUrl().getPort(),
                StandInMode.OK,
                Duration.ZERO,
                new JsonCodec());
        SeatWaveApi api = SeatWaveApi.start(settings, Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
      Map<Integer, Integer> sameKey = new TreeMap<>();
      List<String> sameKeyIds = new ArrayList<>();
      for (ApiClient.Answer answer :
          sendAtOnce(client, concurrency, copy -> holdRequest("A-1", "storm-one-key"))) {
        sameKey.merge(answer.status(), 1, Integer::sum);
        holdIdIn(answer.body()).filter(id -> !sameKeyIds.contains(id)).ifPresent(sameKeyIds::add);
      }
      long sameKeyHeld = countSeatsHeldBy(api, sameKeyIds);
      int pairsRequests = runPairs(client);
      Map<Integer, Integer> sameSeat = new TreeMap<>();
      for (ApiClient.Answer answer :
          sendAtOnce(client, concurrency, fan -> holdRequest("B-1", "fan-" + fan))) {
        sameSeat.merge(answer.status(), 1, Integer::sum);
      }
      return new Report(
          sameKey,
          sameKeyIds.size(),
          sameKeyHeld,
          pairsRequests,
          count(api, "SELECT COUNT(*) FROM bookings"),
          count(
              api,
              "SELECT COUNT(*) FROM (SELECT event_id, seat_label FROM bookings"
                  + " GROUP BY event_id, seat_label HAVING COUNT(*) > 1)"),
          provider.chargeCount(),
          countHoldsChargedTwice(api, provider),
          sameSeat,
          count(
              api,
              "SELECT COUNT(*) FROM seats WHERE event_id = 1 AND seat_label = 'B-1'"
                  + " AND status = 'HELD'"),
          count(api, "SELECT COUNT(*) FROM idempotency_keys"),
          retries,
          new TreeMap<>(statusCounts),
          latencies.summary());
    }
  }

  private int runPairs(ApiClient client) throws InterruptedException {
    List<HttpCall> holds = new ArrayList<>();
    for (int pair = 0; pair < pairs; pair++) {
      HttpCall hold = holdRequest(seatFor(pair), "pair-hold-" + pair);
      holds.add(hold);
      holds.add(hold);
    }
    List<ApiClient.Answer> held = sendAll(client, holds);
    List<HttpCall> confirms = new ArrayList<>();
    for (int index = 0; index < held.size(); index += 2) {
      String holdId = holdIdIn(held.get(index).body()).orElseThrow();
      HttpCall confirm =
          new HttpCall("/v1/bookings", "pair-confirm-" + index, "{\"holdId\":\"" + holdId + "\"}");
      confirms.add(confirm);
      confirms.add(confirm);
    }
    sendAll(client, confirms);
    return holds.size() + confirms.size();
  }

  private String seatFor(int pair) {
    // Sections C onward, so the pairs never touch the seats of scenarios 1 (A) and 3 (B).
    int seatsPerSection = settings.seed().seatsPerSection();
    char section = (char) (FIRST_PAIR_SECTION + pair / seatsPerSection);
    return section + "-" + (pair % seatsPerSection + 1);
  }

  private static HttpCall holdRequest(String seat, String key) {
    return new HttpCall(
        "/v1/holds", key, "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"storm\"}");
  }

  private List<ApiClient.Answer> sendAtOnce(
      ApiClient client, int copies, IntFunction<HttpCall> callFor) throws InterruptedException {
    List<HttpCall> calls = new ArrayList<>();
    for (int copy = 0; copy < copies; copy++) {
      calls.add(callFor.apply(copy));
    }
    return sendAll(client, calls);
  }

  private List<ApiClient.Answer> sendAll(ApiClient client, List<HttpCall> calls)
      throws InterruptedException {
    // All requests start together; the semaphore keeps at most `concurrency` on the wire.
    Semaphore onTheWire = new Semaphore(concurrency);
    List<Future<ApiClient.Answer>> pending = new ArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (HttpCall call : calls) {
        pending.add(
            threads.submit(() -> {
              onTheWire.acquire();
              try {
                return sendWithRetries(client, call);
              } finally {
                onTheWire.release();
              }
            }));
      }
    }
    List<ApiClient.Answer> answers = new ArrayList<>();
    for (Future<ApiClient.Answer> answer : pending) {
      answers.add(resultOf(answer));
    }
    return answers;
  }

  private ApiClient.Answer sendWithRetries(ApiClient client, HttpCall call)
      throws IOException, InterruptedException {
    Duration backoff = FIRST_BACKOFF;
    ApiClient.Answer answer = client.postJson(call.path(), call.key(), call.json());
    record(answer);
    for (int retry = 0; retry < MAX_RETRIES && isRetryable(answer); retry++) {
      Thread.sleep(Duration.ofMillis(jitter(backoff.toMillis())));
      Duration doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
      countRetry();
      // The same key and the same body: a retry, not a new request.
      answer = client.postJson(call.path(), call.key(), call.json());
      record(answer);
    }
    statusCounts.merge(answer.status(), 1, Integer::sum);
    return answer;
  }

  private static boolean isRetryable(ApiClient.Answer answer) {
    // 503 says "try again"; 409 confirm-in-progress says "the first attempt is still running".
    // Both are retried with the same key. Every other answer is final.
    return answer.status() == UNAVAILABLE
        || (answer.status() == CONFLICT && answer.body().contains("/problems/confirm-in-progress"));
  }

  private synchronized long jitter(long capMillis) {
    // SplittableRandom is not thread-safe, so the draw is synchronized; it is not on the hot path.
    return random.nextLong(capMillis + 1);
  }

  private synchronized void countRetry() {
    retries++;
  }

  private synchronized void record(ApiClient.Answer answer) {
    latencies.add(answer.elapsed());
  }

  private static ApiClient.Answer resultOf(Future<ApiClient.Answer> answer)
      throws InterruptedException {
    try {
      return answer.get();
    } catch (ExecutionException failed) {
      throw new IllegalStateException("a storm request failed outright", failed.getCause());
    }
  }

  private static Optional<String> holdIdIn(String body) {
    Matcher matcher = HOLD_ID.matcher(body);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static long countSeatsHeldBy(SeatWaveApi api, List<String> holdIds) throws SQLException {
    long held = 0;
    try (Connection connection = api.database().connect();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM seats WHERE hold_id = ? AND status = 'HELD'")) {
      for (String holdId : holdIds) {
        query.setString(1, holdId);
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          held += rows.getLong(1);
        }
      }
    }
    return held;
  }

  private static long countHoldsChargedTwice(SeatWaveApi api, PaymentStandIn provider)
      throws SQLException {
    long chargedTwice = 0;
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement("SELECT hold_id FROM bookings");
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        if (provider.chargesFor(rows.getString(1)) > 1) {
          chargedTwice++;
        }
      }
    }
    return chargedTwice;
  }

  private static long count(SeatWaveApi api, String sql) throws SQLException {
    // The SQL is one of this class's constants, never input.
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement(sql);
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** One request: where, under which key, with which body. */
  private record HttpCall(String path, String key, String json) {}
}
