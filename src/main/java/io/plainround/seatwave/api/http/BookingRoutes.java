package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.CancelOutcome;
import io.plainround.seatwave.api.booking.ConfirmCommand;
import io.plainround.seatwave.api.booking.ConfirmOutcome;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldCommand;
import io.plainround.seatwave.api.booking.HoldId;
import io.plainround.seatwave.api.booking.HoldOutcome;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.booking.HoldState;
import io.plainround.seatwave.api.booking.KeyedReply;
import io.plainround.seatwave.api.booking.Refusal;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The booking operations of version 1. The two writes that create something, hold and confirm,
 * require an Idempotency-Key; cancel is idempotent by its nature and the reads change nothing.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class BookingRoutes {

  static final String HOLD_ENDPOINT = "POST /v1/holds";
  static final String CONFIRM_ENDPOINT = "POST /v1/bookings";
  // application/json, with at most a UTF-8 charset parameter (RFC 8259 makes UTF-8 the encoding).
  private static final Pattern JSON_MEDIA_TYPE =
      Pattern.compile("application/json\\s*(;\\s*charset=\"?utf-8\"?)?", Pattern.CASE_INSENSITIVE);
  private static final int OK = 200;
  private static final int CREATED = 201;

  private final HoldService holds;
  private final ConfirmService confirms;
  private final JsonCodec json;
  private final Problems problems;
  private final ApiMetrics metrics;
  private final Duration retryAfter;

  BookingRoutes(
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json,
      Problems problems,
      ApiMetrics metrics,
      Duration retryAfter) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.confirms = Objects.requireNonNull(confirms, "confirms");
    this.json = Objects.requireNonNull(json, "json");
    this.problems = Objects.requireNonNull(problems, "problems");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  Reply hold(ApiRequest request) throws SQLException {
    Optional<Reply> unreadable = refuseUnreadableBody(request);
    if (unreadable.isPresent()) {
      return unreadable.get();
    }
    List<FieldError> errors = new ArrayList<>();
    Optional<IdempotencyKey> key = keyOf(request, errors);
    Optional<HoldCommand> command =
        bind(request, V1Views.HoldRequestBody.class, errors)
            .flatMap(body -> holdCommandOf(body, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    HoldCommand valid = command.orElseThrow();
    // The fingerprint is taken from the validated request written back out, so spacing and field
    // order do not count as "a different body".
    String canonical =
        json.write(
            new V1Views.HoldRequestBody(valid.eventId(), valid.seatLabel(), valid.customer()));
    KeyClaim claim =
        new KeyClaim(HOLD_ENDPOINT, key.orElseThrow(), Fingerprint.of(HOLD_ENDPOINT, canonical));
    return keyedReply(
        holds.hold(valid, claim, outcome -> renderHold(outcome, request)), HOLD_ENDPOINT, request);
  }

  Reply confirm(ApiRequest request) throws SQLException {
    Optional<Reply> unreadable = refuseUnreadableBody(request);
    if (unreadable.isPresent()) {
      return unreadable.get();
    }
    List<FieldError> errors = new ArrayList<>();
    Optional<IdempotencyKey> key = keyOf(request, errors);
    Optional<HoldId> holdId =
        bind(request, V1Views.BookingRequestBody.class, errors)
            .flatMap(body -> holdIdOf(body.holdId(), "/holdId", errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    String canonical = json.write(new V1Views.BookingRequestBody(holdId.orElseThrow().value()));
    KeyClaim claim =
        new KeyClaim(
            CONFIRM_ENDPOINT, key.orElseThrow(), Fingerprint.of(CONFIRM_ENDPOINT, canonical));
    return keyedReply(
        confirms.confirm(
            new ConfirmCommand(holdId.get()), claim, outcome -> renderConfirm(outcome, request)),
        CONFIRM_ENDPOINT,
        request);
  }

  Reply getHold(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<HoldId> holdId = holdIdOf(request.pathValues().getFirst(), "holdId", errors);
    if (holdId.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    return holds
        .find(holdId.get())
        .map(hold -> Reply.json(OK, json.write(V1Views.HoldView.of(hold))))
        .orElseGet(() ->
            problems.reply(
                ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId()));
  }

  Reply cancel(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<HoldId> holdId = holdIdOf(request.pathValues().getFirst(), "holdId", errors);
    if (holdId.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    return switch (holds.cancel(holdId.get())) {
      case CancelOutcome.Released() -> Reply.noContent();
      case CancelOutcome.HoldNotFound() ->
          problems.reply(
              ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId());
      case CancelOutcome.TooLate(HoldState state) ->
          problems.reply(
              ProblemType.HOLD_NOT_CANCELLABLE,
              "the hold is " + state.name().toLowerCase(Locale.ROOT),
              request.correlationId());
    };
  }

  Reply getBooking(ApiRequest request) throws SQLException {
    String bookingId = request.pathValues().getFirst();
    if (!HoldId.UUID_TEXT.matcher(bookingId).matches()) {
      return problems.invalid(
          List.of(new FieldError("bookingId", "must be a lowercase UUID")),
          request.correlationId());
    }
    return confirms
        .find(bookingId)
        .map(booking -> Reply.json(OK, json.write(V1Views.BookingView.of(booking))))
        .orElseGet(() ->
            problems.reply(
                ProblemType.NOT_FOUND, "no booking has this id", request.correlationId()));
  }

  private StoredReply renderHold(HoldOutcome outcome, ApiRequest request) {
    return switch (outcome) {
      case HoldOutcome.Held(var hold) ->
          new StoredReply(
              CREATED, "/v1/holds/" + hold.holdId().value(), json.write(V1Views.HoldView.of(hold)));
      case HoldOutcome.SeatTaken() ->
          stored(
              problems.reply(
                  ProblemType.SEAT_UNAVAILABLE,
                  "another fan holds or bought this seat",
                  request.correlationId()));
      case HoldOutcome.SeatNotFound() ->
          stored(
              problems.reply(
                  ProblemType.NOT_FOUND, "no such event or seat", request.correlationId()));
    };
  }

  private StoredReply renderConfirm(ConfirmOutcome outcome, ApiRequest request) {
    return switch (outcome) {
      case ConfirmOutcome.Booked(var booking) ->
          new StoredReply(
              CREATED,
              "/v1/bookings/" + booking.bookingId(),
              json.write(V1Views.BookingView.of(booking)));
      case ConfirmOutcome.HoldNotFound() ->
          stored(
              problems.reply(
                  ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId()));
      case ConfirmOutcome.HoldNotActive(HoldState state) ->
          stored(
              problems.reply(
                  ProblemType.HOLD_NOT_ACTIVE,
                  "the hold is " + state.name().toLowerCase(Locale.ROOT),
                  request.correlationId()));
    };
  }

  private Reply keyedReply(
      Result<KeyedReply, Refusal> result, String endpoint, ApiRequest request) {
    return switch (result) {
      case Result.Success<KeyedReply, Refusal>(KeyedReply keyed) -> {
        metrics.countKeyOutcome(
            endpoint, keyed.source() == KeyedReply.Source.REPLAY ? "replayed" : "first");
        yield replyOf(keyed.reply());
      }
      case Result.Error<KeyedReply, Refusal>(Refusal refusal) ->
          refusalReply(refusal, endpoint, request);
    };
  }

  private Reply refusalReply(Refusal refusal, String endpoint, ApiRequest request) {
    return switch (refusal) {
      case Refusal.KeyReusedWithOtherBody() -> {
        metrics.countKeyOutcome(endpoint, "key_reused");
        yield problems.reply(
            ProblemType.KEY_REUSED, "use a new key for a new request", request.correlationId());
      }
      case Refusal.ConfirmInProgress() -> {
        metrics.countKeyOutcome(endpoint, "in_progress");
        yield problems.reply(
            ProblemType.CONFIRM_IN_PROGRESS,
            "retry with the same key in a moment",
            request.correlationId());
      }
      case Refusal.PaymentUnavailable() -> {
        metrics.countKeyOutcome(endpoint, "payment_unavailable");
        yield problems
            .reply(
                ProblemType.PAYMENT_UNAVAILABLE,
                "nothing was booked; the same key will not charge twice",
                request.correlationId())
            .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
      }
    };
  }

  private static Reply replyOf(StoredReply stored) {
    // Stored errors are problem details; stored successes are plain JSON.
    String contentType =
        stored.status() >= ProblemType.INVALID_REQUEST.status() ? Reply.PROBLEM_JSON : Reply.JSON;
    Reply reply = new Reply(stored.status(), contentType, stored.body(), Map.of());
    return stored.location().isEmpty() ? reply : reply.withHeader("Location", stored.location());
  }

  private static StoredReply stored(Reply reply) {
    return new StoredReply(reply.status(), "", reply.body());
  }

  private Optional<Reply> refuseUnreadableBody(ApiRequest request) {
    if (request.bodyTooLarge()) {
      return Optional.of(
          problems.reply(
              ProblemType.BODY_TOO_LARGE,
              "the limit is " + ContractLimits.MAX_BODY_BYTES + " bytes",
              request.correlationId()));
    }
    boolean isJson =
        request
            .header("Content-Type")
            .map(type -> JSON_MEDIA_TYPE.matcher(type.strip()).matches())
            .orElse(false);
    if (!isJson) {
      return Optional.of(
          problems.reply(
              ProblemType.UNSUPPORTED_MEDIA_TYPE,
              "send Content-Type: application/json",
              request.correlationId()));
    }
    return Optional.empty();
  }

  private <T> Optional<T> bind(ApiRequest request, Class<T> type, List<FieldError> errors) {
    return switch (json.read(request.body(), type)) {
      case Result.Success<T, JsonCodec.BindingProblem>(T body) -> Optional.of(body);
      case Result.Error<T, JsonCodec.BindingProblem>(JsonCodec.BindingProblem problem) -> {
        errors.add(new FieldError(problem.pointer(), problem.detail()));
        yield Optional.empty();
      }
    };
  }

  private static Optional<IdempotencyKey> keyOf(ApiRequest request, List<FieldError> errors) {
    Optional<IdempotencyKey> key =
        request.header("Idempotency-Key").flatMap(IdempotencyKey::fromHeader);
    if (key.isEmpty()) {
      errors.add(
          new FieldError(
              "Idempotency-Key",
              "is required: a quoted string of 1 to 64 of A-Z a-z 0-9 _ -, new for each action"));
    }
    return key;
  }

  private static Optional<HoldCommand> holdCommandOf(
      V1Views.HoldRequestBody body, List<FieldError> errors) {
    int errorsBefore = errors.size();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add(new FieldError("/eventId", "is required: a whole number of 1 or more"));
    }
    if (body.seat() == null || !HoldCommand.SEAT_LABEL.matcher(body.seat()).matches()) {
      errors.add(new FieldError("/seat", "is required: a section A to T, a hyphen, a number"));
    }
    if (body.customer() == null || !HoldCommand.CUSTOMER.matcher(body.customer()).matches()) {
      errors.add(new FieldError("/customer", "is required: 1 to 64 of A-Z a-z 0-9 _ -"));
    }
    if (errors.size() > errorsBefore) {
      return Optional.empty();
    }
    return Optional.of(new HoldCommand(body.eventId(), body.seat(), body.customer()));
  }

  private static Optional<HoldId> holdIdOf(String text, String pointer, List<FieldError> errors) {
    if (text == null || !HoldId.UUID_TEXT.matcher(text).matches()) {
      errors.add(new FieldError(pointer, "must be a lowercase UUID"));
      return Optional.empty();
    }
    return Optional.of(new HoldId(text));
  }
}
