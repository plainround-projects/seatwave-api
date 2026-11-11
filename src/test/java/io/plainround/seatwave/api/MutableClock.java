package io.plainround.seatwave.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that moves only when a test moves it, so expiry is tested without waiting. Thread-safe:
 * the instant is an atomic reference read by the server's threads.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  /**
   * Starts the clock.
   *
   * @param start the first instant
   */
  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /**
   * Moves the clock forward.
   *
   * @param step how far
   */
  public void advance(Duration step) {
    now.updateAndGet(current -> current.plus(step));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("SeatWave keeps every instant in UTC");
  }
}
