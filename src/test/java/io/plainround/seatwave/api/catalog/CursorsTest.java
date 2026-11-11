package io.plainround.seatwave.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CursorsTest {

  private static final SeatFilter AVAILABLE_IN_A =
      new SeatFilter("A", SeatFilter.StateFilter.AVAILABLE);
  private static final int SEAT_NUMBER = 500;
  private static final long EVENT_ID = 7;

  @Test
  void readsBackTheSeatPositionItWrote() {
    Cursors.SeatPosition position = new Cursors.SeatPosition(1, AVAILABLE_IN_A, "A", SEAT_NUMBER);

    assertThat(Cursors.decodeSeat(Cursors.encode(position))).contains(position);
  }

  @Test
  void readsBackTheEventPositionItWrote() {
    Cursors.EventPosition position =
        new Cursors.EventPosition(Instant.parse("2026-12-11T09:00:00Z"), EVENT_ID);

    assertThat(Cursors.decodeEvent(Cursors.encode(position))).contains(position);
  }

  @Test
  void refusesACursorItDidNotMake() {
    String edited =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("s1.1.A.available.A.500;DROP".getBytes(StandardCharsets.UTF_8));

    assertThat(Cursors.decodeSeat(edited)).isEmpty();
    assertThat(Cursors.decodeSeat("%%%")).isEmpty();
    assertThat(
            Cursors.decodeEvent(
                Cursors.encode(new Cursors.SeatPosition(1, AVAILABLE_IN_A, "A", 1))))
        .isEmpty();
  }
}
