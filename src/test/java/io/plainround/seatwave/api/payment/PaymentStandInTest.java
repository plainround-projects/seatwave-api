package io.plainround.seatwave.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The stand-in never pretends: it charges once per key, and a failure is never a 2xx. */
class PaymentStandInTest {

  private static final Duration SLOW_REPLY = Duration.ofMillis(300);
  private static final Duration SHORTER_THAN_SLOW_REPLY = Duration.ofMillis(100);
  private static final int PROVIDER_TIMEOUT_MILLIS = 200;
  private static final BigDecimal PRICE = new BigDecimal("80.00");
  private static final String CHARGE =
      "{\"amount\":\"80.00\",\"currency\":\"USD\",\"reference\":\"hold-1\"}";

  private PaymentStandIn standIn;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws Exception {
    standIn = PaymentStandIn.start(0, StandInMode.OK, SLOW_REPLY, new JsonCodec());
  }

  @AfterEach
  void stop() {
    standIn.close();
    http.close();
  }

  @Test
  void chargesOncePerKeyAndReplaysTheFirstCharge() throws Exception {
    HttpResponse<String> first = charge("\"key-1\"", CHARGE);
    HttpResponse<String> second = charge("\"key-1\"", CHARGE);

    assertThat(first.statusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.statusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(standIn.chargesFor("hold-1")).isEqualTo(1);
  }

  @Test
  void refusesAKeyReusedForAnotherChargeAndAChargeWithoutAKey() throws Exception {
    charge("\"key-1\"", CHARGE);

    assertThat(charge("\"key-1\"", CHARGE.replace("80.00", "1.00")).statusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(charge("no-quotes", CHARGE).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(charge("\"key-2\"", "not json").statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void capturesNothingAndNeverAnswersSuccessWhenToldToFail() throws Exception {
    control("POST", "/control/mode?mode=fail");

    assertThat(charge("\"key-1\"", CHARGE).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(standIn.chargeCount()).isZero();
    assertThat(control("GET", "/control/ledger").body()).contains("\"charges\":0");
  }

  @Test
  void capturesAndThenAnswersLateWhenToldToTimeOut() throws Exception {
    standIn.setMode(StandInMode.CHARGE_THEN_TIMEOUT);
    PaymentClient client =
        new PaymentClient(
            new ApiSettings.Payment(
                URI.create("http://127.0.0.1:" + standIn.port()),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                SHORTER_THAN_SLOW_REPLY,
                Duration.ofSeconds(1)),
            new JsonCodec());

    PaymentOutcome outcome = client.charge("hold-9", PRICE, "USD");

    assertThat(outcome).isEqualTo(new PaymentOutcome.Unavailable(PaymentOutcome.Reason.TIMEOUT));
    assertThat(standIn.chargesFor("hold-9")).isEqualTo(1);
  }

  @Test
  void reportsAProviderThatIsNotThereAsUnreachable() throws Exception {
    int port = standIn.port();
    standIn.close();
    PaymentClient client =
        new PaymentClient(
            new ApiSettings.Payment(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                Duration.ofSeconds(1)),
            new JsonCodec());

    assertThat(client.charge("hold-1", PRICE, "USD"))
        .isInstanceOf(PaymentOutcome.Unavailable.class);
    standIn = PaymentStandIn.start(0, StandInMode.OK, SLOW_REPLY, new JsonCodec());
  }

  @Test
  void answersUnknownControlRequestsWithNotFound() throws Exception {
    assertThat(control("POST", "/control/mode?mode=maybe").statusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(control("POST", "/control/mode?mode=ok").body()).isEqualTo("{\"mode\":\"ok\"}");
  }

  private HttpResponse<String> charge(String key, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + standIn.port() + "/v1/charges"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> control(String method, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + standIn.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
