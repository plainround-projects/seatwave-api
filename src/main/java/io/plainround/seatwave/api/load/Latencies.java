package io.plainround.seatwave.api.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Percentiles of measured latencies, by the nearest-rank method. Not thread-safe. */
final class Latencies {

  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private final List<Duration> samples = new ArrayList<>();

  void add(Duration sample) {
    samples.add(sample);
  }

  String summary() {
    if (samples.isEmpty()) {
      return "no samples";
    }
    List<Duration> ordered = new ArrayList<>(samples);
    Collections.sort(ordered);
    return String.format(
        Locale.ROOT,
        "p50 %.2f ms, p99 %.2f ms, max %.2f ms (%d samples)",
        millis(rank(ordered, P50)),
        millis(rank(ordered, P99)),
        millis(ordered.getLast()),
        ordered.size());
  }

  private static Duration rank(List<Duration> ordered, double quantile) {
    // Nearest rank: the smallest value with at least that share of samples at or below it.
    int index = (int) Math.ceil(quantile * ordered.size()) - 1;
    return ordered.get(Math.max(0, index));
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
