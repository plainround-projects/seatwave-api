package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

  private static final Instant NOW = TestApi.START;
  private static final int OLD_KEYS = 3;
  private static final int BATCH = 2;
  private final IdempotencyStore store = new IdempotencyStore();

  @Test
  void purgesOnlyExpiredKeysAndAtMostABatch() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection connection = database.connect()) {
      for (int index = 0; index < OLD_KEYS; index++) {
        connection.setAutoCommit(false);
        store.claim(connection, claim("old-" + index), NOW);
        connection.commit();
      }
      connection.setAutoCommit(false);
      store.claim(connection, claim("young"), NOW.plus(ContractLimits.KEY_RETENTION));
      connection.commit();
      connection.setAutoCommit(true);

      Instant later = NOW.plus(ContractLimits.KEY_RETENTION);
      assertThat(store.purgeExpired(connection, later, BATCH)).isEqualTo(BATCH);
      assertThat(store.purgeExpired(connection, later, BATCH)).isEqualTo(OLD_KEYS - BATCH);
      assertThat(store.count(connection)).isEqualTo(1);
    }
  }

  @Test
  void completesAKeyOnceAndReplaysWhatWasStored() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      KeyClaim claim = claim("k");
      store.claim(connection, claim, NOW);
      StoredReply reply = new StoredReply(HttpStatus.CREATED, "/v1/holds/x", "{}");

      assertThat(store.complete(connection, claim, reply)).isTrue();
      assertThat(store.complete(connection, claim, reply)).isFalse();
      connection.commit();
      assertThat(store.claim(connection, claim, NOW)).isEqualTo(new ClaimResult.Replay(reply));
      assertThat(
              store.claim(
                  connection,
                  new KeyClaim("POST /v1/holds", claim.key(), Fingerprint.of("x", "y")),
                  NOW))
          .isEqualTo(new ClaimResult.BodyMismatch());
    }
  }

  private static KeyClaim claim(String key) {
    return new KeyClaim(
        "POST /v1/holds", new IdempotencyKey(key), Fingerprint.of("POST /v1/holds", key));
  }
}
