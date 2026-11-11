package io.plainround.seatwave.api.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.AllAtOnce;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retry test of Module 7: it attacks the hold's idempotency for real, over HTTP, and proves "no
 * double booking" with counts read from the database, never from the replies.
 */
class HoldRetryTest {

  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final int CONCURRENT_RETRIES = 100;
  private static final int SEQUENTIAL_REPEATS = 10;
  private static final Duration SERVER_FINISHES_WITHIN = Duration.ofSeconds(5);
  private static final String LIVE_HOLDS_OF_SEAT =
      "SELECT COUNT(*) FROM seats WHERE event_id = 1 AND seat_label = ? AND status = 'HELD'"
          + " AND hold_expires_at > ?";

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
  void repeatsTheSameHoldWithoutActingAgain() throws Exception {
    Set<String> holdIds = new HashSet<>();

    for (int attempt = 0; attempt < SEQUENTIAL_REPEATS; attempt++) {
      TestApi.Response reply = api.hold("A-1", "same-request");
      assertThat(reply.status()).isEqualTo(HttpStatus.CREATED);
      holdIds.add(holdIdIn(reply.body()));
    }

    assertThat(holdIds).hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE hold_id = ?", holdIds.iterator().next()))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }

  @Test
  void rejectsTheSameKeyWithADifferentBody() throws Exception {
    api.hold("A-1", "one-key");

    TestApi.Response reply = api.hold("A-2", "one-key");

    assertThat(reply.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(reply.body()).contains("/problems/idempotency-key-reused");
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-2", TestApi.START.toEpochMilli())).isZero();
  }

  @Test
  void treatsReorderedFieldsAsTheSameBody() throws Exception {
    String first = api.hold("A-1", "reordered").body();

    TestApi.Response retry =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("reordered"),
            "{ \"customer\": \"fan_1\", \"seat\": \"A-1\", \"eventId\": 1 }");

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(holdIdIn(retry.body())).isEqualTo(holdIdIn(first));
  }

  @Test
  void holdsOnceWhenOneHundredRetriesWithOneKeyRaceEachOther() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.hold("A-1", "racing-retries"));

    assertThat(replies)
        .allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED));
    assertThat(replies.stream().map(reply -> holdIdIn(reply.body())).distinct()).hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }

  @Test
  void letsOneOfOneHundredFansWithTheirOwnKeysHoldTheSeat() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, fan -> () -> api.hold("A-1", "fan-" + fan));

    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CREATED)).hasSize(1);
    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CONFLICT))
        .hasSize(CONCURRENT_RETRIES - 1);
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-1", TestApi.START.toEpochMilli())).isEqualTo(1);
  }

  @Test
  void returnsTheFirstHoldToARetryAfterALostReply() throws Exception {
    sendAndHangUp(TestApi.holdBody("A-1", "fan_1"), "lost-reply");
    // The server finishes the hold although nobody reads the reply.
    await()
        .atMost(SERVER_FINISHES_WITHIN)
        .until(() ->
            api.count(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE state = 'COMPLETED'"
                        + " AND idempotency_key = ?",
                    "lost-reply")
                == 1);

    TestApi.Response retry = api.hold("A-1", "lost-reply");

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE hold_id = ?", holdIdIn(retry.body())))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
  }

  @Test
  void treatsARetryAfterTheKeyExpiredAsANewRequest() throws Exception {
    String firstHold = holdIdIn(api.hold("A-1", "late-retry").body());
    api.clock().advance(ContractLimits.KEY_RETENTION.plusSeconds(1));

    TestApi.Response lateRetry = api.hold("A-1", "late-retry");

    // The first hold expired after 300 s, so the seat was free again: the late retry is a new hold.
    assertThat(lateRetry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(holdIdIn(lateRetry.body())).isNotEqualTo(firstHold);
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-1", api.clock().instant().toEpochMilli()))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }

  @Test
  void leavesNeitherSeatNorKeyWhenTheProcessDiesBetweenEffectAndKeyWrite() throws Exception {
    api.execute(FailingKeyWrite.INSTALL);

    TestApi.Response crashed = api.hold("A-1", "crash");

    assertThat(crashed.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isZero();
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isZero();

    api.execute(FailingKeyWrite.REMOVE);
    List<TestApi.Response> retries =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.hold("A-1", "crash"));

    assertThat(retries)
        .allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED));
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }

  @Test
  void replaysAStoredRefusalEvenAfterTheSeatIsFreeAgain() throws Exception {
    String otherFansHold = holdIdIn(api.hold("A-1", "other-fan").body());
    assertThat(api.hold("A-1", "refused").status()).isEqualTo(HttpStatus.CONFLICT);
    api.send("DELETE", "/v1/holds/" + otherFansHold, Map.of(), "");

    TestApi.Response sameKey = api.hold("A-1", "refused");
    TestApi.Response newKey = api.hold("A-1", "refused-then-new");

    assertThat(sameKey.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(newKey.status()).isEqualTo(HttpStatus.CREATED);
  }

  private void sendAndHangUp(String body, String key) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String head =
        "POST /v1/holds HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
            + "Idempotency-Key: \""
            + key
            + "\"\r\nContent-Length: "
            + bytes.length
            + "\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), api.port());
        OutputStream out = socket.getOutputStream()) {
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(bytes);
      out.flush();
      // Close without reading: the reply is lost, as when a phone loses its signal.
    }
  }

  static String holdIdIn(String body) {
    Matcher matcher = HOLD_ID.matcher(body);
    assertThat(matcher.find()).as("a holdId in %s", body).isTrue();
    return matcher.group(1);
  }
}
