package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.booking.Booking;
import io.plainround.seatwave.api.booking.Hold;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.SeatSummary;
import java.util.Locale;

/**
 * The JSON shapes of version 1, one record per schema of the contract, so Jackson writes exactly
 * the fields the contract lists. Times are ISO-8601 in UTC; Module 1's epoch milliseconds stay on
 * the version 0 paths only.
 */
final class V1Views {

  private V1Views() {}

  record EventView(long eventId, String name, String startsAt) {
    static EventView of(EventSummary event) {
      return new EventView(event.eventId(), event.name(), event.startsAt().toString());
    }
  }

  record SeatView(String seat, String section, String state) {
    static SeatView of(SeatSummary seat) {
      return new SeatView(
          seat.seatLabel(), seat.section(), seat.state().name().toLowerCase(Locale.ROOT));
    }
  }

  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold) {
      return new HoldView(
          hold.holdId().value(),
          hold.eventId(),
          hold.seatLabel(),
          hold.state().name().toLowerCase(Locale.ROOT),
          hold.expiresAt().toString());
    }
  }

  record BookingView(
      String bookingId,
      String holdId,
      long eventId,
      String seat,
      String paymentId,
      String amount,
      String currency,
      String confirmedAt) {
    static BookingView of(Booking booking) {
      return new BookingView(
          booking.bookingId(),
          booking.holdId().value(),
          booking.eventId(),
          booking.seatLabel(),
          booking.paymentId(),
          booking.amount().toPlainString(),
          booking.currency(),
          booking.confirmedAt().toString());
    }
  }

  record HealthView(String status) {}

  /**
   * The body of POST /v1/holds as sent. Boxed and unchecked on purpose: a missing field must reach
   * the validation step, which names it, instead of failing inside the JSON reader.
   */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  /** The body of POST /v1/bookings as sent. */
  record BookingRequestBody(String holdId) {}
}
