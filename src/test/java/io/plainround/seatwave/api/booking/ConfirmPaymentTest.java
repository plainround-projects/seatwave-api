package io.plainround.seatwave.api.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.AllAtOnce;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.payment.StandInMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The interview question as tests: a client times out and retries a payment request; the API must
 * not charge twice. Charges are counted in the provider's ledger and bookings in the database.
 */
class ConfirmPaymentTest {

  private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\":\"([0-9a-f-]{36})\"");
  private static final int CONCURRENT_RETRIES = 50;
  // The lease a process that died mid-payment left behind.
  private static final Duration DEAD_PROCESS_LEASE = Duration.ofSeconds(2);

  private TestApi api;
  private String holdId;

  @BeforeEach
  void startApiAndHold() throws Exception {
    api = TestApi.start();
    holdId = HoldRetryTest.holdIdIn(api.hold("A-1", "hold-for-confirm").body());
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }

  @Test
  void confirmsOnceAndChargesOnceWhenTheConfirmIsRepeated() throws Exception {
    String first = bookingIdIn(api.confirm(holdId, "pay-once").body());

    TestApi.Response repeat = api.confirm(holdId, "pay-once");

    assertThat(repeat.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(bookingIdIn(repeat.body())).isEqualTo(first);
    assertThat(repeat.header("Location")).isEqualTo("/v1/bookings/" + first);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }

  @Test
  void chargesOnceWhenTheProviderTimesOutAfterCapturingAndTheClientRetries() throws Exception {
    api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT);

    TestApi.Response timedOut = api.confirm(holdId, "timeout-then-retry");

    assertThat(timedOut.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(timedOut.body()).contains("/problems/payment-unavailable");
    assertThat(timedOut.header("Retry-After")).isEqualTo("1");
    // The money moved, but nothing is booked and the seat is never resold blindly.
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isZero();
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'CONFIRMING'")).isEqualTo(1);

    api.provider().setMode(StandInMode.OK);
    TestApi.Response retried = api.confirm(holdId, "timeout-then-retry");

    assertThat(retried.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }

  @Test
  void chargesNothingWhenTheProviderFailsAndBooksOnTheRetry() throws Exception {
    api.provider().setMode(StandInMode.FAIL);

    assertThat(api.confirm(holdId, "fail-then-retry").status())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(api.provider().chargeCount()).isZero();

    api.provider().setMode(StandInMode.OK);
    assertThat(api.confirm(holdId, "fail-then-retry").status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }

  @Test
  void booksOnceWhenFiftyConfirmsWithOneKeyRaceEachOther() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.confirm(holdId, "racing-confirms"));

    // Each racer either gets the booking or is told the first attempt is still paying
    // (409).
    assertThat(replies)
        .allSatisfy(
            reply -> assertThat(reply.status()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
    assertThat(
            replies.stream()
                .filter(reply -> reply.status() == HttpStatus.CREATED)
                .map(reply -> bookingIdIn(reply.body()))
                .distinct())
        .hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }

  @Test
  void refusesASecondKeyForAHoldThatIsAlreadyBooked() throws Exception {
    api.confirm(holdId, "first-key");

    TestApi.Response secondKey = api.confirm(holdId, "second-key");

    assertThat(secondKey.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(secondKey.body()).contains("/problems/hold-not-active").contains("confirmed");
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }

  @Test
  void doesNotChargeAgainWhenARetryArrivesAfterTheKeyExpired() throws Exception {
    api.confirm(holdId, "expired-key");
    api.clock().advance(ContractLimits.KEY_RETENTION.plusSeconds(1));

    TestApi.Response late = api.confirm(holdId, "expired-key");

    // The key is forgotten, so this is a new request; the hold is booked, so it is refused.
    assertThat(late.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }

  @Test
  void takesOverAConfirmWhoseProcessDiedAfterLeasingTheSeat() throws Exception {
    // What a process leaves when it dies after step 1: the seat leased, the key in progress.
    long leaseUntil = TestApi.START.plus(DEAD_PROCESS_LEASE).toEpochMilli();
    api.execute(
        "UPDATE seats SET status = 'CONFIRMING', confirm_lease_until = ? WHERE hold_id = ?",
        leaseUntil,
        holdId);
    String fingerprint =
        Fingerprint.of("POST /v1/bookings", "{\"holdId\":\"" + holdId + "\"}").hex();
    api.execute(
        "INSERT INTO idempotency_keys (endpoint, idempotency_key, fingerprint, state, created_at,"
            + " expires_at) VALUES ('POST /v1/bookings', 'died-mid-payment', ?, 'IN_PROGRESS', ?, ?)",
        fingerprint,
        TestApi.START.toEpochMilli(),
        TestApi.START.plus(ContractLimits.KEY_RETENTION).toEpochMilli());

    assertThat(api.confirm(holdId, "died-mid-payment").status()).isEqualTo(HttpStatus.CONFLICT);
    api.clock().advance(DEAD_PROCESS_LEASE.plusSeconds(1));
    TestApi.Response takeover = api.confirm(holdId, "died-mid-payment");

    assertThat(takeover.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }

  @Test
  void refusesToConfirmAnExpiredACancelledOrAnUnknownHold() throws Exception {
    String cancelled = HoldRetryTest.holdIdIn(api.hold("A-2", "to-cancel").body());
    api.send("DELETE", "/v1/holds/" + cancelled, Map.of(), "");
    api.clock().advance(ContractLimits.HOLD_DURATION.plusSeconds(1));

    TestApi.Response expired = api.confirm(holdId, "too-late");
    TestApi.Response wasCancelled = api.confirm(cancelled, "after-cancel");
    TestApi.Response unknown = api.confirm("00000000-0000-4000-8000-000000000000", "unknown");

    assertThat(expired.body()).contains("/problems/hold-not-active").contains("expired");
    assertThat(wasCancelled.body()).contains("/problems/hold-not-active").contains("cancelled");
    assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.provider().chargeCount()).isZero();
  }

  private static String bookingIdIn(String body) {
    Matcher matcher = BOOKING_ID.matcher(body);
    assertThat(matcher.find()).as("a bookingId in %s", body).isTrue();
    return matcher.group(1);
  }
}
