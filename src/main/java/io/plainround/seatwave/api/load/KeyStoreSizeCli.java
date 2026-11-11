package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Measures what one stored key costs on disk: fills a fresh H2 file database under {@code
 * build/key-size/} with keys that each hold a hold's real reply, compacts it, and prints the file
 * size with and without the keys. Exit status 2 means bad usage; 1 a failure to run.
 */
public final class KeyStoreSizeCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final Pattern COUNT = Pattern.compile("[1-9][0-9]{0,6}");
  private static final int MAX_KEYS = 1_000_000;
  // Commit every 1,000 keys: one transaction per key would measure fsyncs, not bytes.
  private static final int KEYS_PER_COMMIT = 1_000;
  private static final Path FOLDER = Path.of("build", "key-size");
  private static final String ENDPOINT = "POST /v1/holds";
  private static final int CREATED = 201;

  private KeyStoreSizeCli() {}

  /**
   * Measures the bytes per key.
   *
   * @param arguments how many keys to store, 1 to 1,000,000
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1
        || !COUNT.matcher(arguments[0]).matches()
        || Integer.parseInt(arguments[0]) > MAX_KEYS) {
      System.err.println("usage: keyStoreSize <keys, 1 to 1000000>");
      System.exit(EXIT_BAD_USAGE);
    }
    int keys = Integer.parseInt(arguments[0]);
    try {
      long empty = sizeAfter(0, "empty");
      long full = sizeAfter(keys, "full");
      System.out.printf(
          Locale.ROOT,
          "H2 file database, compacted: %,d bytes with no keys, %,d bytes with %,d keys%n",
          empty,
          full,
          keys);
      System.out.printf(
          Locale.ROOT,
          "bytes per stored key, table and indexes: %.0f%n",
          (full - empty) / (double) keys);
    } catch (IOException | SQLException failed) {
      System.err.println("measurement failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static long sizeAfter(int keys, String name) throws IOException, SQLException {
    Files.createDirectories(FOLDER);
    Path file = FOLDER.resolve(name + ".mv.db");
    Files.deleteIfExists(file);
    String url = "jdbc:h2:file:" + FOLDER.resolve(name).toAbsolutePath();
    IdempotencyStore store = new IdempotencyStore();
    Instant now = Instant.now();
    try (Database database = Database.open(url);
        Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      for (int index = 0; index < keys; index++) {
        String holdId = UUID.randomUUID().toString();
        KeyClaim claim =
            new KeyClaim(
                ENDPOINT,
                new IdempotencyKey(UUID.randomUUID().toString()),
                Fingerprint.of(ENDPOINT, holdId));
        store.claim(connection, claim, now);
        // The same reply a real hold stores: 131 bytes of JSON and its Location.
        store.complete(
            connection,
            claim,
            new StoredReply(
                CREATED,
                "/v1/holds/" + holdId,
                "{\"holdId\":\""
                    + holdId
                    + "\",\"eventId\":1,\"seat\":\"A-7\",\"state\":\"held\","
                    + "\"expiresAt\":\"2026-11-11T09:05:00.000000Z\"}"));
        if ((index + 1) % KEYS_PER_COMMIT == 0) {
          connection.commit();
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
      try (Statement statement = connection.createStatement()) {
        // Compacting writes the live data densely, so the file size is the data, not free space.
        statement.execute("SHUTDOWN COMPACT");
      }
    }
    return Files.size(file);
  }
}
