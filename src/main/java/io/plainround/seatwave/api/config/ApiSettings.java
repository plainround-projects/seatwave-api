package io.plainround.seatwave.api.config;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything a SeatWave API process needs that may differ between runs, read from one settings file
 * with no defaults. The limits the contract publishes (page sizes, body size, hold time, key
 * retention) are not here: they live in {@link io.plainround.seatwave.api.ContractLimits}, because
 * changing them would change the contract.
 *
 * @param httpPort the TCP port on 127.0.0.1; 0 lets the system pick one (tests only)
 * @param maxInFlight the most requests served at once; one more is refused with 503
 * @param databaseUrl the H2 JDBC URL
 * @param seed how many events and seats to create in an empty database
 * @param payment where the payment provider is and how long to wait for it
 * @param seatPrice the price of one seat, the amount charged at confirm
 * @param currency the ISO 4217 code of the price
 * @param retryAfter the delay a 503 asks clients to wait before a retry
 * @param version0 the dates announced for the Module 1 paths
 */
public record ApiSettings(
    int httpPort,
    int maxInFlight,
    String databaseUrl,
    Seed seed,
    Payment payment,
    BigDecimal seatPrice,
    String currency,
    Duration retryAfter,
    Version0 version0) {

  // Module 1 seeds 20 sections (A to T) of 1,000 seats: 20,000 seats in all.
  private static final int MAX_SECTIONS = 20;
  private static final int MAX_SEATS_PER_SECTION = 1_000;
  private static final int MAX_EVENTS = 10_000;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_RETRY_AFTER_SECONDS = 60;
  private static final Pattern H2_URL = Pattern.compile("jdbc:h2:(mem|file):[A-Za-z0-9_./;=-]+");
  // The provider is a stand-in on this machine: no request may leave the loopback address.
  private static final Pattern LOOPBACK_URL = Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{2,5}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  /** Checks the fields, so invalid settings cannot exist. */
  public ApiSettings {
    if (httpPort < 0 || httpPort > HIGHEST_PORT) {
      throw new IllegalArgumentException("httpPort out of range: " + httpPort);
    }
    if (maxInFlight < 1 || maxInFlight > MAX_IN_FLIGHT) {
      throw new IllegalArgumentException("maxInFlight out of range: " + maxInFlight);
    }
    if (!H2_URL.matcher(Objects.requireNonNull(databaseUrl, "databaseUrl")).matches()) {
      throw new IllegalArgumentException("databaseUrl must be an H2 URL");
    }
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(payment, "payment");
    if (Objects.requireNonNull(seatPrice, "seatPrice").signum() <= 0 || seatPrice.scale() != 2) {
      throw new IllegalArgumentException("seatPrice must be positive with two decimals");
    }
    if (!CURRENCY.matcher(Objects.requireNonNull(currency, "currency")).matches()) {
      throw new IllegalArgumentException("currency must be three capital letters");
    }
    if (Objects.requireNonNull(retryAfter, "retryAfter").toSeconds() < 1) {
      throw new IllegalArgumentException("retryAfter must be at least one second");
    }
    Objects.requireNonNull(version0, "version0");
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<ApiSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int port = reader.intBetween("http.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
    int inFlight = reader.intBetween("http.maxInFlight", 1, MAX_IN_FLIGHT);
    String url = reader.matching("database.url", H2_URL, "an H2 URL such as jdbc:h2:mem:seatwave");
    Seed seed =
        new Seed(
            reader.intBetween("seed.events", 1, MAX_EVENTS),
            reader.intBetween("seed.sections", 1, MAX_SECTIONS),
            reader.intBetween("seed.seatsPerSection", 1, MAX_SEATS_PER_SECTION));
    URI paymentUrl =
        URI.create(
            reader
                .matching("payment.url", LOOPBACK_URL, "http://127.0.0.1:<port>")
                // A missing or invalid URL is already a problem line; this stand-in only lets the
                // reading go on, so every other problem is reported in the same run.
                .transform(text -> text.isEmpty() ? "http://127.0.0.1:1" : text));
    Duration connect = reader.millisBetween("payment.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("payment.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration lease = reader.millisBetween("payment.leaseMillis", 1, MAX_TIMEOUT_MILLIS);
    BigDecimal price = reader.amount("booking.seatPrice");
    String currency = reader.matching("booking.currency", CURRENCY, "three capital letters");
    int retryAfterSeconds = reader.intBetween("http.retryAfterSeconds", 1, MAX_RETRY_AFTER_SECONDS);
    Instant deprecatedAt = reader.instant("version0.deprecatedAt");
    Instant sunsetAt = reader.instant("version0.sunsetAt");
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    try {
      return Result.success(
          new ApiSettings(
              port,
              inFlight,
              url,
              seed,
              new Payment(paymentUrl, connect, request, lease),
              price,
              currency,
              Duration.ofSeconds(retryAfterSeconds),
              new Version0(deprecatedAt, sunsetAt)));
    } catch (IllegalArgumentException crossFieldProblem) {
      // Each key was valid alone; a rule between two keys failed (for example the lease).
      return Result.error(List.of(crossFieldProblem.getMessage()));
    }
  }

  /**
   * The demo data created in an empty database: Module 1's "Arena Night" with its seats, then
   * further events without seats, so the event list has pages to walk.
   *
   * @param events how many events
   * @param sections how many sections, A onward, the first event has
   * @param seatsPerSection how many seats each section has
   */
  public record Seed(int events, int sections, int seatsPerSection) {

    /** Checks the fields. */
    public Seed {
      if (events < 1 || events > MAX_EVENTS) {
        throw new IllegalArgumentException("events out of range: " + events);
      }
      if (sections < 1 || sections > MAX_SECTIONS) {
        throw new IllegalArgumentException("sections out of range: " + sections);
      }
      if (seatsPerSection < 1 || seatsPerSection > MAX_SEATS_PER_SECTION) {
        throw new IllegalArgumentException("seatsPerSection out of range: " + seatsPerSection);
      }
    }
  }

  /**
   * The payment provider and the budget for calling it.
   *
   * @param baseUrl the provider, on 127.0.0.1
   * @param connectTimeout how long to wait for its TCP connection
   * @param requestTimeout how long to wait for its whole reply
   * @param lease how long a confirm owns a seat while it pays; must outlast both timeouts
   */
  public record Payment(
      URI baseUrl, Duration connectTimeout, Duration requestTimeout, Duration lease) {

    /** Checks the fields. */
    public Payment {
      Objects.requireNonNull(baseUrl, "baseUrl");
      if (!LOOPBACK_URL.matcher(baseUrl.toString()).matches()) {
        throw new IllegalArgumentException("payment.url must be http://127.0.0.1:<port>");
      }
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      Objects.requireNonNull(lease, "lease");
      // A lease shorter than the call would let a second confirm take the seat over while the
      // first is still waiting for the provider.
      if (lease.compareTo(connectTimeout.plus(requestTimeout)) <= 0) {
        throw new IllegalArgumentException(
            "payment.leaseMillis must be longer than the connect and request timeouts together");
      }
    }
  }

  /**
   * The deprecation of the Module 1 paths.
   *
   * @param deprecatedAt when they were deprecated, sent in the Deprecation header
   * @param sunsetAt when they stop answering, sent in the Sunset header
   */
  public record Version0(Instant deprecatedAt, Instant sunsetAt) {

    /** Checks the fields. */
    public Version0 {
      Objects.requireNonNull(deprecatedAt, "deprecatedAt");
      Objects.requireNonNull(sunsetAt, "sunsetAt");
      if (!sunsetAt.isAfter(deprecatedAt)) {
        throw new IllegalArgumentException("version0.sunsetAt must be after version0.deprecatedAt");
      }
    }
  }
}
