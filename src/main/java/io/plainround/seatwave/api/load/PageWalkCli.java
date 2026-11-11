package io.plainround.seatwave.api.load;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for the pagination consistency test. Exit status 0 means the cursor walk
 * skipped and repeated nothing; 3 means it did; 2 bad usage; 1 a failure to run.
 */
public final class PageWalkCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int EXIT_INCONSISTENT = 3;
  private static final int MAX_CHANGES = 1_000;

  private PageWalkCli() {}

  /**
   * Runs the walk described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting for a reply
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: pageWalk <page-walk.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      PageWalk.Changes changes =
          new PageWalk.Changes(
              reader.intBetween("walk.pageSize", 1, ContractLimits.MAX_PAGE_LIMIT),
              reader.intBetween("walk.holdsPerPage", 0, MAX_CHANGES),
              reader.intBetween("walk.insertsPerPage", 0, MAX_CHANGES));
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) -> {
          PageWalk.Report report = new PageWalk(settings, changes).run();
          print(report, changes);
          boolean isConsistent = report.cursor().skipped() == 0 && report.cursor().repeated() == 0;
          System.exit(isConsistent ? 0 : EXIT_INCONSISTENT);
        }
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | SQLException failed) {
      System.err.println("walk failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static void print(PageWalk.Report report, PageWalk.Changes changes) {
    System.out.printf(
        Locale.ROOT,
        "Java %s on %s %s; SeatWave API in this process on 127.0.0.1; H2 in memory%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"));
    System.out.printf(
        Locale.ROOT,
        "walk: every available seat of event 1, %d per page; after each page fans hold %d seats"
            + " and the venue adds %d (same seeded schedule for both walks)%n",
        changes.pageSize(),
        changes.holdsPerPage(),
        changes.insertsPerPage());
    System.out.printf(
        Locale.ROOT,
        "%-8s %6s %9s %13s %8s %9s%n",
        "walk",
        "pages",
        "returned",
        "stable seats",
        "skipped",
        "repeated");
    printWalk("cursor", report.cursor());
    printWalk("offset", report.offset());
    System.out.printf(
        Locale.ROOT,
        "cursor pages: %d bytes on average, %d at most; latency %s%n",
        report.averagePageBytes(),
        report.largestPageBytes(),
        report.pageLatency());
  }

  private static void printWalk(String name, PageWalk.Walk walk) {
    System.out.printf(
        Locale.ROOT,
        "%-8s %6d %9d %13d %8d %9d%n",
        name,
        walk.pages(),
        walk.returned(),
        walk.stableSeats(),
        walk.skipped(),
        walk.repeated());
  }
}
