package io.plainround.seatwave.api.http;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters and latency histograms, written in the Prometheus text format at GET /metrics.
 *
 * <p>Labels are the route template (for example {@code GET /v1/holds/{holdId}}), the status code
 * and the key outcome: a few dozen values in all. Hold ids, keys and customers never become labels,
 * because each new label value is a new series to store (guideline: low-cardinality labels).
 *
 * <p>Thread-safe: every counter is a {@link LongAdder} in a concurrent map, so many request threads
 * count at once without a lock.
 */
final class ApiMetrics {

  // Upper bounds of the latency buckets, in milliseconds; p50 and p99 are read from them.
  private static final long[] BUCKET_BOUNDS_MILLIS = {
    1, 2, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500
  };
  private static final String SEPARATOR = "\u0000";
  private static final long NANOS_PER_MICRO = 1_000;
  private static final double MICROS_PER_MILLI = 1_000.0;

  private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final Map<String, LongAdder[]> buckets = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> durationSumMicros = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> keyOutcomes = new ConcurrentHashMap<>();

  void countRequest(String route, int status, Duration elapsed) {
    requests.computeIfAbsent(route + SEPARATOR + status, unused -> new LongAdder()).increment();
    LongAdder[] routeBuckets = buckets.computeIfAbsent(route, unused -> newBuckets());
    long millis = elapsed.toMillis();
    for (int index = 0; index < BUCKET_BOUNDS_MILLIS.length; index++) {
      if (millis <= BUCKET_BOUNDS_MILLIS[index]) {
        routeBuckets[index].increment();
      }
    }
    // The last bucket is +Inf: every request falls in it.
    routeBuckets[BUCKET_BOUNDS_MILLIS.length].increment();
    durationSumMicros
        .computeIfAbsent(route, unused -> new LongAdder())
        .add(elapsed.toNanos() / NANOS_PER_MICRO);
  }

  void countKeyOutcome(String endpoint, String outcome) {
    keyOutcomes
        .computeIfAbsent(endpoint + SEPARATOR + outcome, unused -> new LongAdder())
        .increment();
  }

  /**
   * Writes every series.
   *
   * @param keysStored the number of rows in the idempotency-key table now
   * @return the Prometheus text exposition
   */
  String render(long keysStored) {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    sorted(requests)
        .forEach((labels, count) -> {
          String[] parts = labels.split(SEPARATOR);
          text.append(
              String.format(
                  Locale.ROOT,
                  "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d%n",
                  parts[0],
                  parts[1],
                  count.sum()));
        });
    text.append("# TYPE seatwave_request_duration_milliseconds histogram\n");
    new TreeMap<>(buckets)
        .forEach((route, routeBuckets) -> appendHistogram(text, route, routeBuckets));
    text.append("# TYPE seatwave_idempotency_outcomes_total counter\n");
    sorted(keyOutcomes)
        .forEach((labels, count) -> {
          String[] parts = labels.split(SEPARATOR);
          text.append(
              String.format(
                  Locale.ROOT,
                  "seatwave_idempotency_outcomes_total{endpoint=\"%s\",outcome=\"%s\"} %d%n",
                  parts[0],
                  parts[1],
                  count.sum()));
        });
    text.append("# TYPE seatwave_idempotency_keys_stored gauge\n");
    text.append(String.format(Locale.ROOT, "seatwave_idempotency_keys_stored %d%n", keysStored));
    return text.toString();
  }

  private void appendHistogram(StringBuilder text, String route, LongAdder[] routeBuckets) {
    for (int index = 0; index < BUCKET_BOUNDS_MILLIS.length; index++) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_request_duration_milliseconds_bucket{route=\"%s\",le=\"%d\"} %d%n",
              route,
              BUCKET_BOUNDS_MILLIS[index],
              routeBuckets[index].sum()));
    }
    long count = routeBuckets[BUCKET_BOUNDS_MILLIS.length].sum();
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_bucket{route=\"%s\",le=\"+Inf\"} %d%n",
            route,
            count));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_sum{route=\"%s\"} %.3f%n",
            route,
            durationSumMicros.get(route).sum() / MICROS_PER_MILLI));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_count{route=\"%s\"} %d%n",
            route,
            count));
  }

  private static Map<String, LongAdder> sorted(Map<String, LongAdder> counters) {
    return new TreeMap<>(counters);
  }

  private static LongAdder[] newBuckets() {
    LongAdder[] fresh = new LongAdder[BUCKET_BOUNDS_MILLIS.length + 1];
    for (int index = 0; index < fresh.length; index++) {
      fresh[index] = new LongAdder();
    }
    return fresh;
  }
}
