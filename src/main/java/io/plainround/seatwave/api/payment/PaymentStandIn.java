package io.plainround.seatwave.api.payment;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for a payment provider, on 127.0.0.1 only. It is not a payment system: it moves no
 * money and talks to nothing. It keeps a ledger in memory, deduplicates charges by Idempotency-Key
 * as real providers do, and can be told to time out after charging or to fail without charging.
 *
 * <p>Endpoints: {@code POST /v1/charges} (the charge), {@code POST /control/mode?mode=ok|
 * charge_then_timeout|fail} and {@code GET /control/ledger} (for the lab and the tests).
 *
 * <p>Thread-safe: the ledger is a concurrent map updated with one atomic {@code computeIfAbsent}
 * per key, so two racing charges with one key capture once.
 */
public final class PaymentStandIn implements AutoCloseable {

  private static final int MAX_BODY_BYTES = 1_024;
  private static final int OK = 200;
  private static final int CREATED = 201;
  // A charge sent again with the same key answers 200 with the first charge, as providers do.
  private static final int REPLAYED = 200;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int KEY_REUSED = 422;
  private static final int UNAVAILABLE = 503;
  // The JDK server's backlog: 0 lets the operating system choose.
  private static final int SYSTEM_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;

  private final HttpServer server;
  private final ExecutorService workers;
  private final JsonCodec json;
  private final Duration slowReply;
  private final AtomicReference<StandInMode> mode;
  private final Map<String, Charge> chargesByKey = new ConcurrentHashMap<>();

  private PaymentStandIn(
      HttpServer server, ExecutorService workers, JsonCodec json, StandInMode mode, Duration slow) {
    this.server = server;
    this.workers = workers;
    this.json = json;
    this.mode = new AtomicReference<>(mode);
    this.slowReply = slow;
  }

  /**
   * Starts the stand-in on 127.0.0.1.
   *
   * @param port the port, or 0 for one the system picks
   * @param mode how it behaves at first
   * @param slowReply how long CHARGE_THEN_TIMEOUT waits before answering
   * @param json the JSON codec
   * @return the running stand-in
   * @throws IOException if the port cannot be bound
   */
  public static PaymentStandIn start(int port, StandInMode mode, Duration slowReply, JsonCodec json)
      throws IOException {
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), SYSTEM_BACKLOG);
    // One virtual thread per call: a slow reply parks a virtual thread, not a platform thread.
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    PaymentStandIn standIn =
        new PaymentStandIn(
            server,
            workers,
            Objects.requireNonNull(json, "json"),
            Objects.requireNonNull(mode, "mode"),
            Objects.requireNonNull(slowReply, "slowReply"));
    server.setExecutor(workers);
    server.createContext("/v1/charges", standIn::handleCharge);
    server.createContext("/control/", standIn::handleControl);
    server.start();
    return standIn;
  }

  /**
   * The port it listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  /**
   * Changes how it behaves from the next charge on.
   *
   * @param newMode the mode
   */
  public void setMode(StandInMode newMode) {
    mode.set(Objects.requireNonNull(newMode, "newMode"));
  }

  /**
   * Counts captured charges.
   *
   * @return the number of charges captured since start, one per provider key
   */
  public int chargeCount() {
    return chargesByKey.size();
  }

  /**
   * Counts captured charges for one reference.
   *
   * @param reference SeatWave's reference, the hold id
   * @return how many charges carry it; more than 1 would be a double charge
   */
  public long chargesFor(String reference) {
    return chargesByKey.values().stream()
        .filter(charge -> charge.reference().equals(reference))
        .count();
  }

  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.close();
  }

  private void handleCharge(HttpExchange exchange) throws IOException {
    try (exchange) {
      Optional<IdempotencyKey> key =
          IdempotencyKey.fromHeader(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      if (!"POST".equals(exchange.getRequestMethod()) || key.isEmpty()) {
        send(exchange, BAD_REQUEST, "{\"error\":\"POST with an Idempotency-Key\"}");
        return;
      }
      Result<ChargeRequest, JsonCodec.BindingProblem> body =
          json.read(readBody(exchange), ChargeRequest.class);
      if (!(body
          instanceof
          Result.Success<ChargeRequest, JsonCodec.BindingProblem>(ChargeRequest request))) {
        send(exchange, BAD_REQUEST, "{\"error\":\"malformed charge\"}");
        return;
      }
      StandInMode current = mode.get();
      if (current == StandInMode.FAIL) {
        // Asked to fail: no capture, and never a 2xx.
        send(exchange, UNAVAILABLE, "{\"error\":\"provider failing on purpose\"}");
        return;
      }
      String newPaymentId = "pay_" + UUID.randomUUID();
      Charge charge =
          chargesByKey.computeIfAbsent(
              key.get().value(), unused -> new Charge(newPaymentId, request));
      if (!charge.request().equals(request)) {
        send(exchange, KEY_REUSED, "{\"error\":\"key reused with a different charge\"}");
        return;
      }
      if (current == StandInMode.CHARGE_THEN_TIMEOUT) {
        // The money has moved; the reply arrives after the caller stopped waiting.
        waitBeforeReplying(slowReply);
      }
      int status = charge.paymentId().equals(newPaymentId) ? CREATED : REPLAYED;
      send(exchange, status, json.write(new ChargeReply(charge.paymentId(), "captured")));
    }
  }

  private void handleControl(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      if ("GET".equals(exchange.getRequestMethod()) && "/control/ledger".equals(path)) {
        Map<String, Long> byReference = new TreeMap<>();
        chargesByKey
            .values()
            .forEach(charge -> byReference.merge(charge.reference(), 1L, Long::sum));
        send(exchange, OK, json.write(ledgerOf(byReference)));
        return;
      }
      String query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");
      if ("POST".equals(exchange.getRequestMethod()) && "/control/mode".equals(path)) {
        for (StandInMode candidate : StandInMode.values()) {
          if (query.equals("mode=" + candidate.name().toLowerCase(Locale.ROOT))) {
            mode.set(candidate);
            send(exchange, OK, "{\"mode\":\"" + query.substring("mode=".length()) + "\"}");
            return;
          }
        }
      }
      send(exchange, NOT_FOUND, "{\"error\":\"unknown control request\"}");
    }
  }

  private Map<String, Object> ledgerOf(Map<String, Long> byReference) {
    // A LinkedHashMap keeps the field order, so the JSON reads the same on every run.
    Map<String, Object> ledger = new LinkedHashMap<>();
    ledger.put("charges", chargesByKey.size());
    ledger.put("byReference", byReference);
    return ledger;
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream bodyStream = exchange.getRequestBody()) {
      return new String(bodyStream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
    }
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream responseStream = exchange.getResponseBody()) {
      responseStream.write(bytes);
    }
  }

  private static void waitBeforeReplying(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException interrupted) {
      // The stand-in is closing: keep the flag and answer at once.
      Thread.currentThread().interrupt();
    }
  }

  private record Charge(String paymentId, ChargeRequest request) {
    String reference() {
      return request.reference();
    }
  }
}
