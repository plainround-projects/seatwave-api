package io.plainround.seatwave.api;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point: serves the SeatWave API on 127.0.0.1 for a fixed time. Exit status 2
 * means bad usage or settings; 1 means the database or the port failed.
 */
public final class ServeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int MAX_SERVE_SECONDS = 3_600;

  private ServeCli() {}

  /**
   * Serves the API described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <serve.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) ->
            serve(settings, Duration.ofSeconds(seconds));
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | SQLException failed) {
      System.err.println("serving failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static void serve(ApiSettings settings, Duration serveFor)
      throws SQLException, IOException, InterruptedException {
    try (SeatWaveApi api = SeatWaveApi.start(settings, Clock.systemUTC())) {
      System.out.printf(
          Locale.ROOT,
          "SeatWave API on http://127.0.0.1:%d for %d s (at most %d requests in flight)%n",
          api.port(),
          serveFor.toSeconds(),
          settings.maxInFlight());
      System.out.printf(
          Locale.ROOT, "payment provider expected at %s%n", settings.payment().baseUrl());
      Thread.sleep(serveFor);
    }
  }
}
