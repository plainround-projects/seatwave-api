package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.catalog.CatalogProblem;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.Page;
import io.plainround.seatwave.api.catalog.Position;
import io.plainround.seatwave.api.catalog.SeatFilter;
import io.plainround.seatwave.api.catalog.SeatSummary;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GET /v1/events and GET /v1/events/{eventId}/seats: every parameter is checked against the
 * contract's rules before the database is touched.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class CatalogRoutes {

  static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,200}");
  private static final Pattern SECTION = Pattern.compile("[A-T]");
  private static final Set<String> EVENT_PARAMETERS = Set.of("limit", "cursor");
  private static final Set<String> SEAT_PARAMETERS = Set.of("section", "state", "limit", "cursor");
  private static final int OK = 200;

  private final CatalogService catalog;
  private final JsonCodec json;
  private final Problems problems;

  CatalogRoutes(CatalogService catalog, JsonCodec json, Problems problems) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.json = Objects.requireNonNull(json, "json");
    this.problems = Objects.requireNonNull(problems, "problems");
  }

  Reply listEvents(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<Parameters> parameters = parameters(request, EVENT_PARAMETERS, errors);
    Optional<Integer> limit = parameters.flatMap(found -> limitOf(found, errors));
    Optional<Position> position = parameters.flatMap(found -> positionOf(found, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    Result<Page<EventSummary>, CatalogProblem> page =
        catalog.listEvents(limit.orElseThrow(), position.orElseThrow());
    return switch (page) {
      case Result.Success<Page<EventSummary>, CatalogProblem>(Page<EventSummary> events) ->
          Reply.json(OK, json.write(events.map(V1Views.EventView::of)));
      case Result.Error<Page<EventSummary>, CatalogProblem>(CatalogProblem problem) ->
          problemReply(problem, request);
    };
  }

  Reply listSeats(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    String eventIdText = request.pathValues().getFirst();
    if (!EVENT_ID.matcher(eventIdText).matches()) {
      errors.add(new FieldError("eventId", "must be a whole number of 1 or more"));
    }
    Optional<Parameters> parameters = parameters(request, SEAT_PARAMETERS, errors);
    Optional<SeatFilter> filter = parameters.flatMap(found -> filterOf(found, errors));
    Optional<Integer> limit = parameters.flatMap(found -> limitOf(found, errors));
    Optional<Position> position = parameters.flatMap(found -> positionOf(found, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    Result<Page<SeatSummary>, CatalogProblem> page =
        catalog.listSeats(
            Long.parseLong(eventIdText),
            filter.orElseThrow(),
            limit.orElseThrow(),
            position.orElseThrow());
    return switch (page) {
      case Result.Success<Page<SeatSummary>, CatalogProblem>(Page<SeatSummary> seats) ->
          Reply.json(OK, json.write(seats.map(V1Views.SeatView::of)));
      case Result.Error<Page<SeatSummary>, CatalogProblem>(CatalogProblem problem) ->
          problemReply(problem, request);
    };
  }

  private Reply problemReply(CatalogProblem problem, ApiRequest request) {
    return switch (problem) {
      case CatalogProblem.EventNotFound() ->
          problems.reply(ProblemType.NOT_FOUND, "no event has this id", request.correlationId());
      case CatalogProblem.BadCursor(String detail) ->
          problems.invalid(List.of(new FieldError("cursor", detail)), request.correlationId());
    };
  }

  private static Optional<Parameters> parameters(
      ApiRequest request, Set<String> allowed, List<FieldError> errors) {
    return switch (Parameters.parse(request.rawQuery(), allowed)) {
      case Result.Success<Parameters, List<FieldError>>(Parameters found) -> Optional.of(found);
      case Result.Error<Parameters, List<FieldError>>(List<FieldError> bad) -> {
        errors.addAll(bad);
        yield Optional.empty();
      }
    };
  }

  private static Optional<Integer> limitOf(Parameters parameters, List<FieldError> errors) {
    return parameters.intBetween(
        "limit", 1, ContractLimits.MAX_PAGE_LIMIT, ContractLimits.DEFAULT_PAGE_LIMIT, errors);
  }

  private static Optional<Position> positionOf(Parameters parameters, List<FieldError> errors) {
    Optional<String> cursor = parameters.get("cursor");
    if (cursor.isEmpty()) {
      return Optional.of(new Position.First());
    }
    if (!CURSOR.matcher(cursor.get()).matches()) {
      errors.add(new FieldError("cursor", "must be a nextCursor value, unchanged"));
      return Optional.empty();
    }
    return Optional.of(new Position.After(cursor.get()));
  }

  private static Optional<SeatFilter> filterOf(Parameters parameters, List<FieldError> errors) {
    String section = parameters.get("section").orElse(SeatFilter.ANY_SECTION);
    boolean isSectionValid =
        section.equals(SeatFilter.ANY_SECTION) || SECTION.matcher(section).matches();
    if (!isSectionValid) {
      errors.add(new FieldError("section", "must be one letter from A to T"));
    }
    Optional<String> stateText = parameters.get("state");
    Optional<SeatFilter.StateFilter> state =
        stateText.isEmpty()
            ? Optional.of(SeatFilter.StateFilter.ANY)
            : stateOf(stateText.get(), errors);
    if (!isSectionValid || state.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new SeatFilter(section, state.get()));
  }

  private static Optional<SeatFilter.StateFilter> stateOf(String text, List<FieldError> errors) {
    return switch (text) {
      case "available", "held", "sold" ->
          Optional.of(SeatFilter.StateFilter.valueOf(text.toUpperCase(Locale.ROOT)));
      default -> {
        errors.add(new FieldError("state", "must be available, held or sold"));
        yield Optional.empty();
      }
    };
  }
}
