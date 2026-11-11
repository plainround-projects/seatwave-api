package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

  private static final Instant NOW = TestApi.START;
  private static final int OLD_KEYS = 3;
  private static final int BATCH = 2;
  // Generous for a wait that takes milliseconds, so only a request that never blocks or never
  // finishes fails the test.
  private static final Duration LOCK_WAIT_LIMIT = Duration.ofSeconds(5);
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

  @Test
  void letsOneOfTwoRequestsWithOneExpiredKeyWorkAndReplaysItsReplyToTheOther() throws Exception {
    // The first connection is declared last so it closes first: a failed assertion then ends its
    // transaction and frees the second request instead of leaving it waiting on the row lock.
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection watcher = database.connect();
        Connection second = database.connect();
        Connection first = database.connect()) {
      KeyClaim claim = claim("expired");
      first.setAutoCommit(false);
      store.claim(first, claim, NOW);
      store.complete(first, claim, new StoredReply(HttpStatus.CREATED, "/v1/holds/old", "{}"));
      first.commit();
      Instant later = NOW.plus(ContractLimits.KEY_RETENTION);
      StoredReply newReply = new StoredReply(HttpStatus.CREATED, "/v1/holds/new", "{\"new\":1}");

      // The first request takes the expired key over and keeps its transaction open.
      assertThat(store.claim(first, claim, later)).isEqualTo(new ClaimResult.Claimed());
      // The second reads the same expired row now, before the first commits: the interleaving
      // that used to leave it with an IN_PROGRESS key and nothing to complete.
      second.setAutoCommit(false);
      CompletableFuture<ClaimResult> secondResult = new CompletableFuture<>();
      Thread.ofPlatform()
          .start(() -> {
            try {
              secondResult.complete(store.claim(second, claim, later));
            } catch (SQLException failure) {
              secondResult.completeExceptionally(failure);
            }
          });
      // H2 spins rather than parks while it waits for a row lock, so the wait is read from H2.
      await().atMost(LOCK_WAIT_LIMIT).until(() -> secondResult.isDone() || isBlocked(watcher));
      store.complete(first, claim, newReply);
      first.commit();

      assertThat(secondResult.get(LOCK_WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(new ClaimResult.Replay(newReply));
      second.rollback();
      assertThat(store.count(first)).isEqualTo(1);
    }
  }

  private static boolean isBlocked(Connection watcher) throws SQLException {
    try (PreparedStatement query =
            watcher.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL");
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }

  private static KeyClaim claim(String key) {
    return new KeyClaim(
        "POST /v1/holds", new IdempotencyKey(key), Fingerprint.of("POST /v1/holds", key));
  }
}
