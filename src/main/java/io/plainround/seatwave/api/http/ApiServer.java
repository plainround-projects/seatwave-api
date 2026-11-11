package io.plainround.seatwave.api.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The HTTP server: the JDK's own {@code com.sun.net.httpserver} (an exported, supported JDK API),
 * on 127.0.0.1 only, one virtual thread per request, and a hard limit on requests in flight.
 *
 * <p>This class is the boundary. It turns bytes into an {@link ApiRequest}, picks the route, and
 * maps every failure to a response once: an overload to 503 service-busy, a database failure to 503
 * storage-unavailable, a bug to 500, all as problem details.
 *
 * <p>Thread-safe: the route table is immutable, the limit is a semaphore, and the metrics are
 * concurrent counters.
 */
public final class ApiServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int SYSTEM_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;
  // sendResponseHeaders takes -1 for "no body" (204), as the JDK's HttpExchange documents.
  private static final long NO_BODY = -1;
  private static final int NO_CONTENT = 204;

  private final HttpServer server;
  private final ExecutorService workers;
  private final Semaphore inFlight;
  private final List<Route> routes;
  private final Problems problems;
  private final ApiMetrics metrics;
  private final Duration retryAfter;

  private ApiServer(
      HttpServer server,
      ExecutorService workers,
      ApiSettings settings,
      List<Route> routes,
      Problems problems,
      ApiMetrics metrics) {
    this.server = server;
    this.workers = workers;
    this.inFlight = new Semaphore(settings.maxInFlight());
    this.routes = List.copyOf(routes);
    this.problems = problems;
    this.metrics = metrics;
    this.retryAfter = settings.retryAfter();
  }

  /**
   * The services the routes call.
   *
   * @param database the database
   * @param keys the idempotency-key store
   * @param catalog events and seats
   * @param holds holds
   * @param confirms confirms
   * @param json the JSON codec
   */
  public record Services(
      Database database,
      IdempotencyStore keys,
      CatalogService catalog,
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json) {

    /** Checks the fields. */
    public Services {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(keys, "keys");
      Objects.requireNonNull(catalog, "catalog");
      Objects.requireNonNull(holds, "holds");
      Objects.requireNonNull(confirms, "confirms");
      Objects.requireNonNull(json, "json");
    }
  }

  /**
   * Starts the server on 127.0.0.1.
   *
   * @param settings the port, the in-flight limit, the Retry-After delay and version 0's dates
   * @param services what the routes call
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static ApiServer start(ApiSettings settings, Services services) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(services, "services");
    Problems problems = new Problems(services.json());
    ApiMetrics metrics = new ApiMetrics();
    List<Route> routes = routesFor(settings, services, problems, metrics);
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.httpPort()),
            SYSTEM_BACKLOG);
    // Virtual threads make a request waiting on the database or the payment provider cheap; the
    // semaphore, not the thread count, is what bounds the work (Module 4).
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    ApiServer api = new ApiServer(server, workers, settings, routes, problems, metrics);
    server.setExecutor(workers);
    server.createContext("/", api::serve);
    server.start();
    return api;
  }

  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.close();
  }

  private static List<Route> routesFor(
      ApiSettings settings, Services services, Problems problems, ApiMetrics metrics) {
    CatalogRoutes catalog = new CatalogRoutes(services.catalog(), services.json(), problems);
    BookingRoutes booking =
        new BookingRoutes(
            services.holds(),
            services.confirms(),
            services.json(),
            problems,
            metrics,
            settings.retryAfter());
    Version0Routes version0 =
        new Version0Routes(
            services.catalog(),
            services.holds(),
            services.confirms(),
            services.json(),
            settings.version0());
    OperationsRoutes operations =
        new OperationsRoutes(services.database(), services.keys(), metrics, services.json());
    return List.of(
        Route.of("GET", "/v1/events", catalog::listEvents),
        Route.of("GET", "/v1/events/{eventId}/seats", catalog::listSeats),
        Route.of("POST", "/v1/holds", booking::hold),
        Route.of("GET", "/v1/holds/{holdId}", booking::getHold),
        Route.of("DELETE", "/v1/holds/{holdId}", booking::cancel),
        Route.of("POST", "/v1/bookings", booking::confirm),
        Route.of("GET", "/v1/bookings/{bookingId}", booking::getBooking),
        Route.of("GET", "/health", operations::health),
        Route.of("GET", "/metrics", operations::metrics),
        Route.of("GET", "/events", version0::listEvents),
        Route.of("GET", "/events/{eventId}/seats", version0::listSeats),
        Route.of("POST", "/holds", version0::hold),
        Route.of("POST", "/bookings", version0::confirm));
  }

  private void serve(HttpExchange exchange) {
    long startNanos = System.nanoTime();
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    String routeLabel = "unmatched";
    int status = ProblemType.INTERNAL_ERROR.status();
    try (exchange) {
      ApiRequest request = read(exchange, correlationId);
      Optional<Route> route = match(request);
      routeLabel = route.map(Route::label).orElse(routeLabel);
      Reply reply = admit(request, route);
      status = reply.status();
      send(exchange, reply);
    } catch (IOException clientGone) {
      // The client closed the connection, before or during the reply: the lost-reply case. Any
      // write already committed stays, and the client's retry with the same key gets it back.
      LOG.warn("the reply could not be sent: {}", clientGone.getMessage());
    } finally {
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      metrics.countRequest(routeLabel, status, elapsed);
      LOG.info("{} {} {} ms", routeLabel, status, elapsed.toMillis());
      MDC.remove("correlationId");
    }
  }

  private Reply admit(ApiRequest request, Optional<Route> route) {
    if (route.isEmpty()) {
      return refuseUnrouted(request);
    }
    // Refuse at once over the limit: a fast 503 keeps the work already admitted fast (Module 4).
    if (!inFlight.tryAcquire()) {
      return problems
          .reply(ProblemType.SERVICE_BUSY, "too many requests in flight", request.correlationId())
          .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
    }
    try {
      return route.get().handler().handle(request.withPathValues(route.get().valuesIn(request)));
    } catch (SQLException databaseFailure) {
      LOG.error("database failure, SQL state {}", databaseFailure.getSQLState(), databaseFailure);
      return problems
          .reply(
              ProblemType.STORAGE_UNAVAILABLE,
              "nothing was changed; retry with the same key",
              request.correlationId())
          .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
    } catch (RuntimeException bug) {
      LOG.error("unexpected failure", bug);
      return problems.reply(
          ProblemType.INTERNAL_ERROR, "quote the instance to support", request.correlationId());
    } finally {
      inFlight.release();
    }
  }

  private Reply refuseUnrouted(ApiRequest request) {
    List<String> allowed =
        routes.stream()
            .filter(candidate -> candidate.pattern().matcher(request.path()).matches())
            .map(Route::method)
            .toList();
    if (allowed.isEmpty()) {
      return problems.reply(ProblemType.NOT_FOUND, "no such path", request.correlationId());
    }
    return problems
        .reply(
            ProblemType.METHOD_NOT_ALLOWED,
            "this path takes " + String.join(", ", allowed),
            request.correlationId())
        .withHeader("Allow", String.join(", ", allowed));
  }

  private Optional<Route> match(ApiRequest request) {
    return routes.stream()
        .filter(route -> route.method().equals(request.method()))
        .filter(route -> route.pattern().matcher(request.path()).matches())
        .findFirst();
  }

  private static ApiRequest read(HttpExchange exchange, String correlationId) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
    byte[] body;
    try (InputStream bodyStream = exchange.getRequestBody()) {
      // One byte over the limit tells "exactly at the limit" from "too large" without reading on.
      body = bodyStream.readNBytes(ContractLimits.MAX_BODY_BYTES + 1);
    }
    boolean tooLarge = body.length > ContractLimits.MAX_BODY_BYTES;
    return new ApiRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getRawPath(),
        Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""),
        headers,
        tooLarge ? "" : new String(body, StandardCharsets.UTF_8),
        tooLarge,
        List.of(),
        correlationId);
  }

  private static void send(HttpExchange exchange, Reply reply) throws IOException {
    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    if (reply.status() == NO_CONTENT) {
      exchange.sendResponseHeaders(NO_CONTENT, NO_BODY);
      return;
    }
    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
    exchange.sendResponseHeaders(reply.status(), body.length);
    try (OutputStream responseStream = exchange.getResponseBody()) {
      responseStream.write(body);
    }
  }

  /** What a route runs. */
  @FunctionalInterface
  interface RouteHandler {
    Reply handle(ApiRequest request) throws SQLException;
  }

  /**
   * One method and path template.
   *
   * @param method the HTTP method
   * @param template the path as the contract writes it, also the metrics label
   * @param pattern the template as a regular expression, one group per {@code {name}}
   * @param handler what runs
   */
  private record Route(String method, String template, Pattern pattern, RouteHandler handler) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z]+\\}");

    static Route of(String method, String template, RouteHandler handler) {
      // Each {name} matches one path segment; the handler checks its format against the contract.
      String regex =
          PLACEHOLDER
              .splitAsStream(template)
              .map(Pattern::quote)
              .collect(Collectors.joining("([^/]+)"));
      if (template.endsWith("}")) {
        regex += "([^/]+)";
      }
      return new Route(method, template, Pattern.compile(regex), handler);
    }

    String label() {
      return method + " " + template;
    }

    List<String> valuesIn(ApiRequest request) {
      Matcher matcher = pattern.matcher(request.path());
      if (!matcher.matches()) {
        throw new IllegalStateException("a matched route no longer matches");
      }
      List<String> values = new ArrayList<>();
      for (int group = 1; group <= matcher.groupCount(); group++) {
        values.add(matcher.group(group));
      }
      return values;
    }
  }
}
