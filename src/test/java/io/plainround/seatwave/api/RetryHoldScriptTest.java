package io.plainround.seatwave.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The guarded script, run only in ways that send nothing: the dry run, and a run that has nobody to
 * confirm it. No server is started, so a request that slipped through would fail to connect.
 */
class RetryHoldScriptTest {

  private static final String SCRIPT = "scripts/retry-hold.sh";
  private static final long RUN_LIMIT_SECONDS = 30;
  private static final int REFUSED = 1;

  @Test
  void printsACurlCommandThatTheShellReadsBackWordForWord() throws Exception {
    Run dryRun = run(new ProcessBuilder("bash", SCRIPT, "8087", "A-8", "4", "fan-8-hold-1"));
    String curlLine =
        dryRun
            .standardOutput()
            .lines()
            .filter(line -> line.startsWith("  curl "))
            .findFirst()
            .orElseThrow()
            .strip();

    // The shell splits the pasted line into words; each word is printed on its own line.
    Run pasted =
        run(new ProcessBuilder("bash", "-c", "set -- " + curlLine + "; printf '%s\\n' \"$@\""));

    assertThat(dryRun.exitStatus()).isZero();
    assertThat(pasted.standardOutput().lines())
        .contains(
            "Content-Type: application/json",
            "Idempotency-Key: \"fan-8-hold-1\"",
            "{\"eventId\":1,\"seat\":\"A-8\",\"customer\":\"script\"}",
            "%{http_code}");
  }

  @Test
  void saysNothingWasSentWhenNoTerminalCanConfirmTheRun() throws Exception {
    Run refused =
        run(
            new ProcessBuilder("bash", SCRIPT, "8087", "A-8", "4", "fan-8-hold-1", "--run")
                .redirectInput(new File("/dev/null")));

    assertThat(refused.exitStatus()).isEqualTo(REFUSED);
    assertThat(refused.standardError()).contains("nothing sent");
    assertThat(refused.standardOutput()).doesNotContain("attempt");
  }

  private record Run(int exitStatus, String standardOutput, String standardError) {}

  private static Run run(ProcessBuilder command) throws IOException, InterruptedException {
    Process process = command.start();
    if (!process.waitFor(RUN_LIMIT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the script did not finish: " + command.command());
    }
    return new Run(
        process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }
}
