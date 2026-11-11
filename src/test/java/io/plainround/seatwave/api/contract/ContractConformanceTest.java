package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract test: real requests against the running service, every reply judged against the
 * OpenAPI document (status code, required fields, the error body's shape, the media type). Every
 * call below goes through {@link TestApi#send}, which fails the test on any mismatch.
 */
class ContractConformanceTest {

  private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");
  private static final Map<String, String> NO_HEADERS = Map.of();

  private TestApi api;

  @BeforeEach
  void startApi() throws Exception {
    api = TestApi.start();
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }

  /** One request the contract forbids, and the status the server must answer it with. */
  private record Forbidden(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      String body,
      int status) {}

  @Test
  void refusesEveryRequestTheContractForbidsBeforeDoingAnyWork() throws Exception {
    List<Forbidden> forbidden =
        List.of(
            new Forbidden(
                "GET", "/v1/events", Map.of("limit", "0"), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events",
                Map.of("limit", "501"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events",
                Map.of("cursor", "not a cursor!"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET", "/v1/events/0/seats", Map.of(), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events/1/seats",
                Map.of("section", "Z"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events/1/seats",
                Map.of("state", "free"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET", "/v1/holds/not-a-uuid", Map.of(), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                JSON,
                TestApi.holdBody("A-1", "fan_1"),
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-1"),
                "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":\"fan_1\",\"price\":0}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-2"),
                "{\"eventId\":-1,\"seat\":\"A-0\",\"customer\":\"\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                Map.of("Content-Type", "application/json", "Idempotency-Key", "no-quotes"),
                TestApi.holdBody("A-1", "fan_1"),
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-4"),
                "{\"eventId\":\"1\",\"seat\":\"A-1\",\"customer\":\"fan_1\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-5"),
                "{\"eventId\":1.5,\"seat\":\"A-1\",\"customer\":\"fan_1\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/bookings",
                Map.of(),
                TestApi.jsonWithKey("k-3"),
                "{\"holdId\":\"NOT-A-UUID\"}",
                HttpStatus.BAD_REQUEST));

    for (Forbidden request : forbidden) {
      assertThat(
              Contract.judgeRequest(
                      request.method(),
                      request.path(),
                      request.query(),
                      request.headers(),
                      request.body())
                  .hasErrors())
          .as("the contract forbids %s", request)
          .isTrue();
      TestApi.Response reply =
          api.send(request.method(), pathWithQuery(request), request.headers(), request.body());
      assertThat(reply.status()).as("the server refuses %s", request).isEqualTo(request.status());
      assertThat(reply.header("Content-Type")).isEqualTo("application/problem+json");
    }
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
  }

  @Test
  void namesEveryInvalidFieldInOneProblem() throws Exception {
    TestApi.Response reply =
        api.send(
            "POST", "/v1/holds", JSON, "{\"eventId\":0,\"seat\":\"Z-9\",\"customer\":\"a b\"}");

    assertThat(reply.body())
        .contains("\"pointer\":\"Idempotency-Key\"")
        .contains("\"pointer\":\"/eventId\"")
        .contains("\"pointer\":\"/seat\"")
        .contains("\"pointer\":\"/customer\"")
        .contains("\"instance\":\"urn:uuid:");
  }

  @Test
  void refusesANumberSentForTheCustomerAndHoldsNothing() throws Exception {
    TestApi.Response reply =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("number-customer"),
            "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":5}");

    assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(reply.body())
        .contains("{\"pointer\":\"/customer\",\"detail\":\"has the wrong type\"}");
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isZero();
  }

  @Test
  void reportsAnUnknownParameterAndABadValueInOneProblem() throws Exception {
    TestApi.Response reply = api.send("GET", "/v1/events?limit=0&foo=1", NO_HEADERS, "");

    assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(reply.body())
        .contains("\"detail\":\"2 fields are invalid\"")
        .contains("\"pointer\":\"limit\"")
        .contains("\"pointer\":\"foo\"");
  }

  @Test
  void refusesABodyThatIsTooLargeOrNotJson() throws Exception {
    TestApi.Response tooLarge =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("big"),
            "{\"x\":\"" + "a".repeat(ContractLimits.MAX_BODY_BYTES) + "\"}");
    TestApi.Response notJson =
        api.send(
            "POST",
            "/v1/holds",
            Map.of("Content-Type", "text/plain", "Idempotency-Key", "\"text\""),
            "eventId=1");
    TestApi.Response malformed =
        api.send("POST", "/v1/holds", TestApi.jsonWithKey("bad"), "{\"eventId\":");

    assertThat(tooLarge.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(notJson.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(malformed.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void pagesThroughTheEventsWithTheCursorItReturns() throws Exception {
    TestApi.Response first = api.send("GET", "/v1/events?limit=2", NO_HEADERS, "");
    String cursor = first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
    TestApi.Response last = api.send("GET", "/v1/events?limit=2&cursor=" + cursor, NO_HEADERS, "");

    assertThat(first.body())
        .contains("\"eventId\":1")
        .contains("\"eventId\":2")
        .contains("nextCursor");
    assertThat(last.body()).contains("\"eventId\":3").doesNotContain("nextCursor");
  }

  @Test
  void filtersSeatsBySectionAndState() throws Exception {
    api.hold("B-3", "filter");

    TestApi.Response held = api.send("GET", "/v1/events/1/seats?state=held", NO_HEADERS, "");
    TestApi.Response sectionC =
        api.send("GET", "/v1/events/1/seats?section=C&limit=500", NO_HEADERS, "");

    assertThat(held.body())
        .isEqualTo("{\"items\":[{\"seat\":\"B-3\",\"section\":\"B\",\"state\":\"held\"}]}");
    assertThat(sectionC.body()).contains("\"C-1\"").contains("\"C-20\"").doesNotContain("\"B-");
  }

  @Test
  void refusesACursorUsedWithOtherFiltersOrAnotherEvent() throws Exception {
    TestApi.Response first =
        api.send("GET", "/v1/events/1/seats?section=A&limit=5", NO_HEADERS, "");
    String cursor = first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

    TestApi.Response otherFilter =
        api.send("GET", "/v1/events/1/seats?section=B&limit=5&cursor=" + cursor, NO_HEADERS, "");
    TestApi.Response eventsCursor = api.send("GET", "/v1/events?cursor=" + cursor, NO_HEADERS, "");

    assertThat(otherFilter.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(otherFilter.body()).contains("another event or filter");
    assertThat(eventsCursor.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void answersUnknownEventsHoldsAndBookingsWithNotFound() throws Exception {
    String unknown = "00000000-0000-4000-8000-000000000000";

    assertThat(api.send("GET", "/v1/events/999/seats", NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("GET", "/v1/holds/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("DELETE", "/v1/holds/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("GET", "/v1/bookings/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.hold("Q-1", "no-such-seat").status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void showsAHoldAndABookingAndCancelsIdempotently() throws Exception {
    TestApi.Response hold = api.hold("A-5", "show");
    String holdPath = hold.header("Location");
    TestApi.Response booking =
        api.confirm(holdPath.substring("/v1/holds/".length()), "show-confirm");

    assertThat(api.send("GET", holdPath, NO_HEADERS, "").body())
        .contains("\"state\":\"confirmed\"");
    assertThat(api.send("GET", booking.header("Location"), NO_HEADERS, "").body())
        .contains("\"amount\":\"80.00\"")
        .contains("\"currency\":\"USD\"");
    assertThat(api.send("DELETE", holdPath, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.CONFLICT);

    String other = api.hold("A-6", "cancel-twice").header("Location");
    assertThat(api.send("DELETE", other, NO_HEADERS, "").status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(api.send("DELETE", other, NO_HEADERS, "").status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(api.send("GET", other, NO_HEADERS, "").body()).contains("\"state\":\"cancelled\"");
    assertThat(
            api.count(
                "SELECT COUNT(*) FROM seats WHERE seat_label = 'A-6' AND status = 'AVAILABLE'"))
        .isEqualTo(1);
  }

  @Test
  void reportsHealthAndMetrics() throws Exception {
    api.hold("A-7", "metrics");
    api.hold("A-7", "metrics");

    TestApi.Response health = api.send("GET", "/health", NO_HEADERS, "");
    TestApi.Response metrics = api.send("GET", "/metrics", NO_HEADERS, "");

    assertThat(health.body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(metrics.body())
        .contains("seatwave_requests_total{route=\"POST /v1/holds\",status=\"201\"} 2")
        .contains(
            "seatwave_idempotency_outcomes_total{endpoint=\"POST /v1/holds\",outcome=\"replayed\"} 1")
        .contains("seatwave_idempotency_keys_stored 1")
        .contains(
            "seatwave_request_duration_milliseconds_bucket{route=\"POST /v1/holds\",le=\"+Inf\"} 2");
  }

  private static String pathWithQuery(Forbidden request) {
    if (request.query().isEmpty()) {
      return request.path();
    }
    StringBuilder query = new StringBuilder(request.path()).append('?');
    request
        .query()
        .forEach((name, value) ->
            query
                .append(name)
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                .append('&'));
    return query.substring(0, query.length() - 1);
  }
}
