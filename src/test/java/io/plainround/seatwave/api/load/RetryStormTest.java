package io.plainround.seatwave.api.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class RetryStormTest {

  private static final int SECTIONS = 4;
  private static final int SEATS_PER_SECTION = 50;
  // In-flight limit 8 against 20 requests at once, so the 503-and-retry path runs too.
  private static final int MAX_IN_FLIGHT = 8;
  private static final int CONCURRENCY = 20;
  private static final int PAIRS = 30;

  @Test
  void findsNoDoubleEffectInTheDatabaseOrTheLedger() throws Exception {
    ApiSettings settings =
        TestApi.settings(
            new ApiSettings.Seed(1, SECTIONS, SEATS_PER_SECTION), MAX_IN_FLIGHT, freePort());

    RetryStorm.Report report = new RetryStorm(settings, CONCURRENCY, PAIRS).run();

    assertThat(report.sameKeyHoldIds()).isEqualTo(1);
    assertThat(report.sameKeySeatsHeld()).isEqualTo(1);
    assertThat(report.pairsBookings()).isEqualTo(PAIRS);
    assertThat(report.seatsBookedTwice()).isZero();
    assertThat(report.providerCharges()).isEqualTo(PAIRS);
    assertThat(report.holdsChargedTwice()).isZero();
    assertThat(report.sameSeatHolders()).isEqualTo(1);
    assertThat(report.sameSeatReplies())
        .containsEntry(HttpStatus.CREATED, 1)
        .containsEntry(HttpStatus.CONFLICT, CONCURRENCY - 1);
    // In-flight limit 8 against 20 at once: some requests were refused and retried with the key.
    assertThat(report.retries()).isPositive();
  }

  private static int freePort() throws Exception {
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return probe.getLocalPort();
    }
  }
}
