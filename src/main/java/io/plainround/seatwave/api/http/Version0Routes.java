package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.ConfirmCommand;
import io.plainround.seatwave.api.booking.ConfirmOutcome;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldCommand;
import io.plainround.seatwave.api.booking.HoldId;
import io.plainround.seatwave.api.booking.HoldOutcome;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.booking.KeyedReply;
import io.plainround.seatwave.api.booking.Refusal;
import io.plainround.seatwave.api.catalog.CatalogProblem;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.Page;
import io.plainround.seatwave.api.catalog.Position;
import io.plainround.seatwave.api.catalog.SeatFilter;
import io.plainround.seatwave.api.catalog.SeatSummary;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Module 1's four paths, answering exactly as they did, so a client built for version 0 keeps
 * working until the sunset date. Every reply says so: a Deprecation header (RFC 9745), a Sunset
 * header (RFC 8594) and a Link to the version 1 replacement (RFC 8288).
 *
 * <p>Two things changed underneath, and only these: a confirm now takes the payment (Module 2's
 * requirement), and each write gets a one-time key, which keeps the old retry behavior (a retry is
 * a new request) while the payment provider's key, made from the hold, still stops a second charge.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class Version0Routes {

  // Module 1's patterns, kept as they were: version 0 accepts what it accepted before.
  private static final Pattern SEAT_LABEL = Pattern.compile("[A-T]-[0-9]{1,4}");
  private static final Pattern SECTION = Pattern.compile("[A-T]");
  // Module 1 seeded 1,000 seats per section and returned a whole section in one reply.
  private static final int SECTION_SIZE = 1_000;
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNAVAILABLE = 503;

  private final CatalogService catalog;
  private final HoldService holds;
  private final ConfirmService confirms;
  private final JsonCodec json;
  private final String deprecation;
  private final String sunset;

  Version0Routes(
      CatalogService catalog,
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json,
      ApiSettings.Version0 dates) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.confirms = Objects.requireNonNull(confirms, "confirms");
    this.json = Objects.requireNonNull(json, "json");
    // RFC 9745: a Structured Field Date, "@" and seconds since the epoch.
    this.deprecation = "@" + dates.deprecatedAt().getEpochSecond();
    // RFC 8594: an HTTP date, as RFC 9110 defines it (RFC 1123 format, always GMT).
    this.sunset =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(dates.sunsetAt().atOffset(ZoneOffset.UTC));
  }

  Reply listEvents(ApiRequest request) throws SQLException {
    List<Version0Views.EventVersion0> events = new ArrayList<>();
    Position position = new Position.First();
    // Version 0 is unpaginated; it walks the version 1 pages and joins them, as Module 1 did.
    while (true) {
      Result<Page<EventSummary>, CatalogProblem> page =
          catalog.listEvents(ContractLimits.MAX_PAGE_LIMIT, position);
      if (!(page instanceof Result.Success<Page<EventSummary>, CatalogProblem>(var found))) {
        throw new IllegalStateException("a cursor this server made was refused");
      }
      found
          .items()
          .forEach(event ->
              events.add(
                  new Version0Views.EventVersion0(
                      event.eventId(), event.name(), event.startsAt().toEpochMilli())));
      if (!(found instanceof Page.More<EventSummary> more)) {
        return deprecated(Reply.json(OK, json.write(events)), "/v1/events");
      }
      position = new Position.After(more.nextCursor());
    }
  }

  Reply listSeats(ApiRequest request) throws SQLException {
    String successor = "/v1/events/" + request.pathValues().getFirst() + "/seats";
    if (!CatalogRoutes.EVENT_ID.matcher(request.pathValues().getFirst()).matches()) {
      return error(BAD_REQUEST, "invalid eventId", successor);
    }
    Result<Parameters, List<FieldError>> parameters =
        Parameters.parse(request.rawQuery(), Set.of("section"));
    String section =
        parameters instanceof Result.Success<Parameters, List<FieldError>>(Parameters found)
            ? found.get("section").orElse("")
            : "";
    if (!SECTION.matcher(section).matches()) {
      return error(BAD_REQUEST, "invalid section", successor);
    }
    Result<Page<SeatSummary>, CatalogProblem> page =
        catalog.listSeats(
            Long.parseLong(request.pathValues().getFirst()),
            new SeatFilter(section, SeatFilter.StateFilter.ANY),
            SECTION_SIZE,
            new Position.First());
    // Module 1 answered an unknown event with an empty list, so version 0 still does.
    List<Version0Views.SeatVersion0> seats =
        page instanceof Result.Success<Page<SeatSummary>, CatalogProblem>(var found)
            ? found.items().stream()
                .map(seat ->
                    new Version0Views.SeatVersion0(
                        seat.seatLabel(), seat.state().name().toLowerCase(Locale.ROOT)))
                .toList()
            : List.of();
    return deprecated(Reply.json(OK, json.write(seats)), successor);
  }

  Reply hold(ApiRequest request) throws SQLException {
    if (request.bodyTooLarge()) {
      return error(TOO_LARGE, "request body too large", "/v1/holds");
    }
    Result<Parameters, List<FieldError>> form =
        Parameters.parse(request.body(), Set.of("eventId", "seat", "customer"));
    if (!(form instanceof Result.Success<Parameters, List<FieldError>>(Parameters fields))) {
      return error(BAD_REQUEST, "malformed form field", "/v1/holds");
    }
    String eventId = fields.get("eventId").orElse("");
    String seat = fields.get("seat").orElse("");
    String customer = fields.get("customer").orElse("");
    if (!CatalogRoutes.EVENT_ID.matcher(eventId).matches()) {
      return error(BAD_REQUEST, "invalid eventId", "/v1/holds");
    }
    if (!SEAT_LABEL.matcher(seat).matches()) {
      return error(BAD_REQUEST, "invalid seat", "/v1/holds");
    }
    if (!HoldCommand.CUSTOMER.matcher(customer).matches()) {
      return error(BAD_REQUEST, "invalid customer", "/v1/holds");
    }
    // Module 1 accepted labels such as A-0 and then found no such seat: still a 409 here.
    if (!HoldCommand.SEAT_LABEL.matcher(seat).matches()) {
      return error(CONFLICT, "seat not available", "/v1/holds");
    }
    HoldCommand command = new HoldCommand(Long.parseLong(eventId), seat, customer);
    Result<KeyedReply, Refusal> held =
        holds.hold(command, oneTimeClaim("POST /holds"), this::renderHold);
    return replyOf(held, "/v1/holds");
  }

  Reply confirm(ApiRequest request) throws SQLException {
    if (request.bodyTooLarge()) {
      return error(TOO_LARGE, "request body too large", "/v1/bookings");
    }
    Result<Parameters, List<FieldError>> form = Parameters.parse(request.body(), Set.of("holdId"));
    String holdId =
        form instanceof Result.Success<Parameters, List<FieldError>>(Parameters fields)
            ? fields.get("holdId").orElse("")
            : "";
    if (!HoldId.UUID_TEXT.matcher(holdId).matches()) {
      return error(BAD_REQUEST, "invalid holdId", "/v1/bookings");
    }
    Result<KeyedReply, Refusal> confirmed =
        confirms.confirm(
            new ConfirmCommand(new HoldId(holdId)),
            oneTimeClaim("POST /bookings"),
            this::renderConfirm);
    return replyOf(confirmed, "/v1/bookings");
  }

  private StoredReply renderHold(HoldOutcome outcome) {
    return switch (outcome) {
      case HoldOutcome.Held(var hold) ->
          new StoredReply(
              CREATED,
              "",
              json.write(
                  new Version0Views.HoldVersion0(
                      hold.holdId().value(), ContractLimits.HOLD_DURATION.toSeconds())));
      case HoldOutcome.SeatTaken(), HoldOutcome.SeatNotFound() ->
          new StoredReply(
              CONFLICT, "", json.write(new Version0Views.ErrorVersion0("seat not available")));
    };
  }

  private StoredReply renderConfirm(ConfirmOutcome outcome) {
    return switch (outcome) {
      case ConfirmOutcome.Booked(var booking) ->
          new StoredReply(
              CREATED, "", json.write(new Version0Views.BookingVersion0(booking.bookingId())));
      case ConfirmOutcome.HoldNotFound(), ConfirmOutcome.HoldNotActive(_) ->
          new StoredReply(
              CONFLICT, "", json.write(new Version0Views.ErrorVersion0("hold missing or expired")));
    };
  }

  private Reply replyOf(Result<KeyedReply, Refusal> result, String successor) {
    return switch (result) {
      case Result.Success<KeyedReply, Refusal>(KeyedReply keyed) ->
          deprecated(Reply.json(keyed.reply().status(), keyed.reply().body()), successor);
      case Result.Error<KeyedReply, Refusal>(Refusal.PaymentUnavailable()) ->
          error(UNAVAILABLE, "payment unavailable", successor);
      // A one-time key is never reused, so only a concurrent confirm of the same hold is left.
      case Result.Error<KeyedReply, Refusal>(Refusal _) ->
          error(CONFLICT, "hold missing or expired", successor);
    };
  }

  private static KeyClaim oneTimeClaim(String endpoint) {
    // A fresh random key per request: version 0 never had retry safety, and still has none.
    IdempotencyKey key = new IdempotencyKey("v0-" + UUID.randomUUID());
    return new KeyClaim(endpoint, key, Fingerprint.of(endpoint, key.value()));
  }

  private Reply error(int status, String message, String successor) {
    return deprecated(
        Reply.json(status, json.write(new Version0Views.ErrorVersion0(message))), successor);
  }

  private Reply deprecated(Reply reply, String successor) {
    return reply
        .withHeader("Deprecation", deprecation)
        .withHeader("Sunset", sunset)
        .withHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
  }
}
