package io.plainround.seatwave.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Module 1's clients keep working, unchanged, and are told every time that they must move. */
class Version0CompatibilityTest {

  private static final Map<String, String> FORM =
      Map.of("Content-Type", "application/x-www-form-urlencoded");

  private TestApi api;

  @BeforeEach
  void startApi() throws Exception {
    api = TestApi.start();
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }

  @Test
  void answersModuleOnesEventListWithEpochMillisecondsAndDeprecationHeaders() throws Exception {
    TestApi.Response events = api.send("GET", "/events", Map.of(), "");

    assertThat(events.status()).isEqualTo(HttpStatus.OK);
    assertThat(events.body()).startsWith("[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":");
    assertThat(events.header("Deprecation")).isEqualTo("@1794355200");
    assertThat(events.header("Sunset")).isEqualTo("Tue, 11 May 2027 00:00:00 GMT");
    assertThat(events.header("Link")).isEqualTo("</v1/events>; rel=\"successor-version\"");
  }

  @Test
  void answersModuleOnesSeatListForOneSection() throws Exception {
    TestApi.Response seats = api.send("GET", "/events/1/seats?section=A", Map.of(), "");
    TestApi.Response badSection = api.send("GET", "/events/1/seats?section=Z", Map.of(), "");
    TestApi.Response unknownEvent = api.send("GET", "/events/999/seats?section=A", Map.of(), "");

    assertThat(seats.body()).startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"}");
    assertThat(badSection.body()).isEqualTo("{\"error\":\"invalid section\"}");
    assertThat(unknownEvent.body()).isEqualTo("[]");
  }

  @Test
  void holdsAndConfirmsWithFormBodiesAndTakesThePaymentNow() throws Exception {
    TestApi.Response hold = api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");
    String holdId = hold.body().replaceAll(".*\"holdId\":\"([^\"]+)\".*", "$1");

    TestApi.Response booking = api.send("POST", "/bookings", FORM, "holdId=" + holdId);

    assertThat(hold.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(hold.body()).endsWith(",\"expiresInSeconds\":300}");
    assertThat(booking.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(booking.body()).startsWith("{\"bookingId\":\"");
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }

  @Test
  void stillAnswersARetryAfterALostReplyWithConflictForTheFansOwnHold() throws Exception {
    // Failure mode 1 as version 0 shows it: no key, so the retry is a new request and loses to the
    // fan's own first hold. The app then holds another seat: one fan, two holds.
    api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");

    TestApi.Response retry = api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");

    assertThat(retry.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(retry.body()).isEqualTo("{\"error\":\"seat not available\"}");
  }

  @Test
  void keepsModuleOnesAnswersForBadInput() throws Exception {
    assertThat(api.send("POST", "/holds", FORM, "eventId=x&seat=A-1&customer=fan_1").body())
        .isEqualTo("{\"error\":\"invalid eventId\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=bad&customer=fan_1").body())
        .isEqualTo("{\"error\":\"invalid seat\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=a%20b").body())
        .isEqualTo("{\"error\":\"invalid customer\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=A-0&customer=fan_1").status())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(api.send("POST", "/holds", FORM, "seat").body())
        .isEqualTo("{\"error\":\"malformed form field\"}");
    assertThat(
            api.send("POST", "/holds", FORM, "x=" + "a".repeat(ContractLimits.MAX_BODY_BYTES))
                .status())
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(api.send("POST", "/bookings", FORM, "holdId=nope").body())
        .isEqualTo("{\"error\":\"invalid holdId\"}");
    assertThat(
            api.send("POST", "/bookings", FORM, "holdId=00000000-0000-4000-8000-000000000000")
                .body())
        .isEqualTo("{\"error\":\"hold missing or expired\"}");
  }
}
