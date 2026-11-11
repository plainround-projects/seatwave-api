package io.plainround.seatwave.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.payment.StandInMode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The boundary: unknown paths, wrong methods and overload, each as a problem body. */
class ApiServerTest {

  private static final int SEATS = 10;
  private static final int ONE_REQUEST_IN_FLIGHT = 1;
  private static final Duration CAPTURE_WITHIN = Duration.ofSeconds(5);

  @Test
  void answersAnUnknownPathAndAWrongMethodWithProblems() throws Exception {
    try (TestApi api = TestApi.start()) {
      TestApi.Response unknown = api.sendUnchecked("GET", "/v2/events", Map.of(), "");
      TestApi.Response wrongMethod = api.sendUnchecked("PUT", "/v1/holds", Map.of(), "");

      assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(unknown.header("Content-Type")).isEqualTo("application/problem+json");
      assertThat(wrongMethod.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
      assertThat(wrongMethod.header("Allow")).isEqualTo("POST");
    }
  }

  @Test
  void refusesAtOnceWithRetryAfterWhenTheInFlightLimitIsReached() throws Exception {
    try (TestApi api = TestApi.start(new ApiSettings.Seed(1, 1, SEATS), ONE_REQUEST_IN_FLIGHT)) {
      String holdId = api.hold("A-1", "slot").header("Location").substring("/v1/holds/".length());
      // A confirm that waits for a slow provider keeps the one slot busy.
      api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT);

      try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<TestApi.Response> slowConfirm =
            background.submit(() -> api.confirm(holdId, "slow-confirm"));
        // The provider has captured: the confirm is now waiting for its reply, holding the slot.
        await().atMost(CAPTURE_WITHIN).until(() -> api.provider().chargeCount() == 1);

        TestApi.Response refused = api.send("GET", "/v1/events", Map.of(), "");

        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.body()).contains("/problems/service-busy");
        assertThat(refused.header("Retry-After")).isEqualTo("1");
        assertThat(slowConfirm.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }
  }
}
