package io.plainround.seatwave.api.payment;

import io.plainround.seatwave.api.config.SettingsReader;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Command-line entry point: runs the stand-in payment provider on 127.0.0.1 for a fixed time and
 * prints its ledger at the end. Exit status 2 means bad usage or settings; 1 means the port failed.
 */
public final class PaymentStandInCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_SERVE_SECONDS = 3_600;
  private static final int MAX_SLOW_REPLY_MILLIS = 60_000;
  private static final Pattern MODE = Pattern.compile("ok|charge_then_timeout|fail");

  private PaymentStandInCli() {}

  /**
   * Runs the stand-in described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: paymentStandIn <payment-stand-in.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int port = reader.intBetween("standIn.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      String mode = reader.matching("standIn.mode", MODE, "ok, charge_then_timeout or fail");
      Duration slow = reader.millisBetween("standIn.slowReplyMillis", 1, MAX_SLOW_REPLY_MILLIS);
      int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
      StandInMode startMode = StandInMode.valueOf(mode.toUpperCase(Locale.ROOT));
      try (PaymentStandIn standIn = PaymentStandIn.start(port, startMode, slow, new JsonCodec())) {
        System.out.printf(
            Locale.ROOT,
            "payment stand-in on http://127.0.0.1:%d for %d s, mode %s; not a payment system%n",
            standIn.port(),
            seconds,
            mode);
        Thread.sleep(Duration.ofSeconds(seconds));
        System.out.printf(Locale.ROOT, "charges captured: %d%n", standIn.chargeCount());
      }
    } catch (IOException failed) {
      System.err.println("stand-in failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
}
