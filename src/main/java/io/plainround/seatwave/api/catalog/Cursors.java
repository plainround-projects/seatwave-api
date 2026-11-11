package io.plainround.seatwave.api.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes and reads cursors. A cursor names the last item of a page by its place in the sort order
 * (keyset pagination), not by a count of items before it, so seats changing state or rows being
 * added and removed before it cannot move it.
 *
 * <p>A cursor is opaque to clients: base64url of a short versioned text. It is not secret and not
 * signed; a client that edits one only moves its own position, and every value is checked against
 * an allow-list when it comes back. The seat cursor carries its filters, so it cannot be replayed
 * under different ones.
 */
final class Cursors {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final Pattern EVENT_CURSOR = Pattern.compile("e1\\.([0-9]{1,18})\\.([0-9]{1,18})");
  private static final Pattern SEAT_CURSOR =
      Pattern.compile(
          "s1\\.([0-9]{1,18})\\.([A-T*])\\.(any|available|held|sold)\\.([A-T])\\.([0-9]{1,4})");

  private Cursors() {}

  /**
   * The position after one event.
   *
   * @param startsAt the event's start, the first sort key
   * @param eventId the event's id, which breaks ties between events at the same time
   */
  record EventPosition(Instant startsAt, long eventId) {}

  /**
   * The position after one seat, under one filter.
   *
   * @param eventId the event the cursor belongs to
   * @param filter the filters it was made with
   * @param section the last seat's section, the first sort key
   * @param seatNumber the last seat's number, the second sort key
   */
  record SeatPosition(long eventId, SeatFilter filter, String section, int seatNumber) {}

  static String encode(EventPosition position) {
    return encodeText("e1." + position.startsAt().toEpochMilli() + "." + position.eventId());
  }

  static String encode(SeatPosition position) {
    return encodeText(
        String.join(
            ".",
            "s1",
            Long.toString(position.eventId()),
            position.filter().section(),
            position.filter().state().name().toLowerCase(Locale.ROOT),
            position.section(),
            Integer.toString(position.seatNumber())));
  }

  static Optional<EventPosition> decodeEvent(String cursor) {
    return decodeText(cursor)
        .map(EVENT_CURSOR::matcher)
        .filter(Matcher::matches)
        .map(matcher ->
            new EventPosition(
                Instant.ofEpochMilli(Long.parseLong(matcher.group(1))),
                Long.parseLong(matcher.group(2))));
  }

  static Optional<SeatPosition> decodeSeat(String cursor) {
    return decodeText(cursor)
        .map(SEAT_CURSOR::matcher)
        .filter(Matcher::matches)
        .map(matcher ->
            new SeatPosition(
                Long.parseLong(matcher.group(1)),
                new SeatFilter(
                    matcher.group(2),
                    SeatFilter.StateFilter.valueOf(matcher.group(3).toUpperCase(Locale.ROOT))),
                matcher.group(4),
                Integer.parseInt(matcher.group(5))));
  }

  private static String encodeText(String text) {
    return ENCODER.encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static Optional<String> decodeText(String cursor) {
    try {
      return Optional.of(new String(DECODER.decode(cursor), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException notBase64) {
      // A cursor that is not base64url is a client error, reported as "bad cursor" by the caller.
      return Optional.empty();
    }
  }
}
