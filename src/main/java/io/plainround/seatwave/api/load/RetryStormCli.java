package io.plainround.seatwave.api.load;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for the retry storm: starts the API and the stand-in provider in this
 * process on 127.0.0.1, fires the storm, and prints what the database and the ledger hold. Exit
 * status 0 means no double effect; 3 means one was found; 2 bad usage; 1 a failure to run.
 */
public final class RetryStormCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int EXIT_DOUBLE_EFFECT = 3;
  private static final int MAX_CONCURRENCY = 1_000;
  private static final int MAX_PAIRS = 18_000;

  private RetryStormCli() {}

  /**
   * Runs the storm described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting for the requests
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: retryStorm <retry-storm.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int concurrency = reader.intBetween("storm.concurrency", 1, MAX_CONCURRENCY);
      int pairs = reader.intBetween("storm.pairs", 1, MAX_PAIRS);
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) -> {
          RetryStorm.Report report = new RetryStorm(settings, concurrency, pairs).run();
          print(report, concurrency, pairs);
          boolean isClean =
              report.sameKeyHoldIds() == 1
                  && report.sameKeySeatsHeld() == 1
                  && report.seatsBookedTwice() == 0
                  && report.holdsChargedTwice() == 0
                  && report.sameSeatHolders() == 1;
          System.exit(isClean ? 0 : EXIT_DOUBLE_EFFECT);
        }
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | SQLException failed) {
      System.err.println("storm failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static void print(RetryStorm.Report report, int concurrency, int pairs) {
    System.out.printf(
        Locale.ROOT,
        "Java %s on %s %s; SeatWave API and the stand-in provider in this process, on 127.0.0.1;"
            + " H2 in memory%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"));
    System.out.printf(
        Locale.ROOT, "scenario 1: one hold sent %d times at once with one key%n", concurrency);
    System.out.printf(Locale.ROOT, "  final replies by status: %s%n", report.sameKeyReplies());
    System.out.printf(
        Locale.ROOT,
        "  distinct hold ids in the replies: %d; seats the database shows held by them: %d%n",
        report.sameKeyHoldIds(),
        report.sameKeySeatsHeld());
    System.out.printf(
        Locale.ROOT,
        "scenario 2: %d holds and %d confirms, each sent twice at once with its own key"
            + " (%d requests)%n",
        pairs,
        pairs,
        report.pairsRequests());
    System.out.printf(
        Locale.ROOT,
        "  database: %d bookings, %d seats booked twice; provider: %d charges, %d holds charged"
            + " twice%n",
        report.pairsBookings(),
        report.seatsBookedTwice(),
        report.providerCharges(),
        report.holdsChargedTwice());
    System.out.printf(
        Locale.ROOT,
        "scenario 3: one seat wanted by %d fans with %d keys%n",
        concurrency,
        concurrency);
    System.out.printf(Locale.ROOT, "  final replies by status: %s%n", report.sameSeatReplies());
    System.out.printf(
        Locale.ROOT, "  database: %d holder(s) of the seat%n", report.sameSeatHolders());
    System.out.printf(
        Locale.ROOT,
        "all requests: final statuses %s; retries (503, or 409 in progress): %d;"
            + " idempotency keys stored: %d%n",
        report.allReplies(),
        report.retries(),
        report.keysStored());
    System.out.printf(Locale.ROOT, "latency of every attempt: %s%n", report.latency());
  }
}
