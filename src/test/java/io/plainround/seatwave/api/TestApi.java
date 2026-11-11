package io.plainround.seatwave.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.report.ValidationReport;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentStandIn;
import io.plainround.seatwave.api.payment.StandInMode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One SeatWave API and one stand-in provider, both on 127.0.0.1 on ports the system picks, an
 * in-memory database of its own and a clock the test moves. Every reply that {@link #send} returns
 * has already been judged against the OpenAPI document: a reply the contract does not allow fails
 * the test that caused it.
 */
public final class TestApi implements AutoCloseable {

  /** The clock's start: a fixed instant, so every run computes the same expiries. */
  public static final Instant START = Instant.parse("2026-11-11T09:00:00Z");

  // A small seed: three events, sections A to C of 20 seats each, on event 1.
  private static final ApiSettings.Seed SMALL_SEED = new ApiSettings.Seed(3, 3, 20);
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration PROVIDER_SLOW_REPLY = Duration.ofSeconds(2);
  private static final int MAX_IN_FLIGHT = 200;
  // The provider budget of the tests: short, so a timeout costs half a second; the lease must
  // outlast connect plus request (ApiSettings.Payment checks it).
  private static final Duration PROVIDER_CONNECT_TIMEOUT = Duration.ofMillis(250);
  private static final Duration PROVIDER_REQUEST_TIMEOUT = Duration.ofMillis(500);
  private static final Duration CONFIRM_LEASE = Duration.ofSeconds(2);

  private final MutableClock clock;
  private final PaymentStandIn provider;
  private final SeatWaveApi api;
  private final HttpClient http;

  private TestApi(MutableClock clock, PaymentStandIn provider, SeatWaveApi api) {
    this.clock = clock;
    this.provider = provider;
    this.api = api;
    this.http = HttpClient.newBuilder().connectTimeout(CLIENT_TIMEOUT).build();
  }

  /**
   * Starts an API on the small seed.
   *
   * @return the running API
   * @throws Exception if it cannot start
   */
  public static TestApi start() throws Exception {
    return start(SMALL_SEED, MAX_IN_FLIGHT);
  }

  /**
   * Starts an API.
   *
   * @param seed what to seed
   * @param maxInFlight the in-flight limit
   * @return the running API
   * @throws Exception if it cannot start
   */
  public static TestApi start(ApiSettings.Seed seed, int maxInFlight) throws Exception {
    MutableClock clock = new MutableClock(START);
    PaymentStandIn provider =
        PaymentStandIn.start(0, StandInMode.OK, PROVIDER_SLOW_REPLY, new JsonCodec());
    try {
      return new TestApi(
          clock, provider, SeatWaveApi.start(settings(seed, maxInFlight, provider.port()), clock));
    } catch (SQLException | IOException | RuntimeException startFailure) {
      provider.close();
      throw startFailure;
    }
  }

  /**
   * Settings for a test API: port 0, a fresh in-memory database, the provider on the given port.
   *
   * @param seed what to seed
   * @param maxInFlight the in-flight limit
   * @param providerPort the provider's port
   * @return the settings
   */
  public static ApiSettings settings(ApiSettings.Seed seed, int maxInFlight, int providerPort) {
    return new ApiSettings(
        0,
        maxInFlight,
        "jdbc:h2:mem:test-" + UUID.randomUUID() + ";LOCK_TIMEOUT=5000",
        seed,
        new ApiSettings.Payment(
            URI.create("http://127.0.0.1:" + providerPort),
            PROVIDER_CONNECT_TIMEOUT,
            PROVIDER_REQUEST_TIMEOUT,
            CONFIRM_LEASE),
        new BigDecimal("80.00"),
        "USD",
        Duration.ofSeconds(1),
        new ApiSettings.Version0(
            Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2027-05-11T00:00:00Z")));
  }

  /**
   * One reply.
   *
   * @param status the status
   * @param headers the headers, names in lower case, first values
   * @param body the body
   */
  public record Response(int status, Map<String, String> headers, String body) {
    /**
     * Reads a header.
     *
     * @param name the name in any case
     * @return its value, or an empty string
     */
    public String header(String name) {
      return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }
  }

  /**
   * Sends a request and checks the reply against the contract.
   *
   * @param method the method
   * @param pathAndQuery the path, with a query if any
   * @param headers request headers
   * @param body the body, or an empty string
   * @return the reply, already judged by the contract
   * @throws Exception if the exchange fails
   */
  public Response send(String method, String pathAndQuery, Map<String, String> headers, String body)
      throws Exception {
    Response response = sendUnchecked(method, pathAndQuery, headers, body);
    String path = URI.create(pathAndQuery).getPath();
    ValidationReport report =
        Contract.judgeReply(method, path, response.status(), response.headers(), response.body());
    assertThat(report.getMessages())
        .as(
            "the contract's judgment of %s %s -> %d %s",
            method, path, response.status(), response.body())
        .isEmpty();
    return response;
  }

  /**
   * Sends a request without judging the reply, for paths the contract does not describe.
   *
   * @param method the method
   * @param pathAndQuery the path, with a query if any
   * @param headers request headers
   * @param body the body, or an empty string
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response sendUnchecked(
      String method, String pathAndQuery, Map<String, String> headers, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + pathAndQuery))
            .timeout(CLIENT_TIMEOUT)
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    headers.forEach(request::header);
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Map<String, String> replyHeaders = new LinkedHashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> replyHeaders.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
    return new Response(response.statusCode(), replyHeaders, response.body());
  }

  /**
   * Holds a seat of event 1.
   *
   * @param seat the seat
   * @param key the Idempotency-Key, without quotes
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response hold(String seat, String key) throws Exception {
    return send("POST", "/v1/holds", jsonWithKey(key), holdBody(seat, "fan_1"));
  }

  /**
   * Confirms a hold.
   *
   * @param holdId the hold
   * @param key the Idempotency-Key, without quotes
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response confirm(String holdId, String key) throws Exception {
    return send("POST", "/v1/bookings", jsonWithKey(key), "{\"holdId\":\"" + holdId + "\"}");
  }

  /**
   * The body of a hold request for event 1.
   *
   * @param seat the seat
   * @param customer the customer
   * @return the JSON
   */
  public static String holdBody(String seat, String customer) {
    return "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"" + customer + "\"}";
  }

  /**
   * The headers of a JSON write with a key.
   *
   * @param key the key, without quotes
   * @return the headers
   */
  public static Map<String, String> jsonWithKey(String key) {
    return Map.of("Content-Type", "application/json", "Idempotency-Key", "\"" + key + "\"");
  }

  /**
   * Reads one number from the database: the counts that prove the invariants come from here, not
   * from the replies.
   *
   * @param sql a query that returns one number
   * @param parameters its parameters
   * @return the number
   * @throws SQLException if the query fails
   */
  public long count(String sql, Object... parameters) throws SQLException {
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        query.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /**
   * Runs one statement against the database, for tests that set up a fault or a leftover state.
   *
   * @param sql the statement, a constant of the test, with ? for every value
   * @param parameters the values
   * @throws SQLException if it fails
   */
  public void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = api.database().connect();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.execute();
    }
  }

  /**
   * The clock the API uses.
   *
   * @return the clock
   */
  public MutableClock clock() {
    return clock;
  }

  /**
   * The stand-in provider.
   *
   * @return the provider
   */
  public PaymentStandIn provider() {
    return provider;
  }

  /**
   * The API's port.
   *
   * @return the port
   */
  public int port() {
    return api.port();
  }

  @Override
  public void close() throws SQLException {
    http.close();
    try {
      api.close();
    } finally {
      provider.close();
    }
  }
}
