package io.plainround.seatwave.api.payment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Calls the payment provider. One instance, and one JDK {@link HttpClient}, per process: the client
 * keeps connections alive across calls (Module 5), so it is created once and shared.
 *
 * <p>Every charge carries the provider's own Idempotency-Key, made from the hold. SeatWave may call
 * the provider any number of times for one hold (a client retry, a takeover after a crash, a retry
 * after SeatWave forgot the client's key); the provider charges once.
 *
 * <p>Thread-safe: {@link HttpClient} is, and this class holds nothing else that changes.
 */
public final class PaymentClient {

  private static final int CREATED = 201;
  private static final int REPLAYED = 200;

  private final URI chargesUri;
  private final ApiSettings.Payment settings;
  private final JsonCodec json;
  private final HttpClient httpClient;

  /**
   * Creates the client.
   *
   * @param settings where the provider is and how long to wait
   * @param json the JSON codec
   */
  public PaymentClient(ApiSettings.Payment settings, JsonCodec json) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.json = Objects.requireNonNull(json, "json");
    this.chargesUri = settings.baseUrl().resolve("/v1/charges");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(settings.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  /**
   * The provider key for a hold: the same hold always gives the same key.
   *
   * @param holdId the hold
   * @return the key SeatWave sends to the provider
   */
  public static String providerKeyFor(String holdId) {
    return "seatwave-hold-" + Objects.requireNonNull(holdId, "holdId");
  }

  /**
   * Charges for one hold, at most once whatever the number of calls.
   *
   * @param holdId the hold, which names the charge
   * @param amount the amount
   * @param currency the currency
   * @return captured, or unavailable with the reason
   */
  public PaymentOutcome charge(String holdId, BigDecimal amount, String currency) {
    String body = json.write(new ChargeRequest(amount.toPlainString(), currency, holdId));
    HttpRequest request =
        HttpRequest.newBuilder(chargesUri)
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            // The provider follows the same draft as SeatWave: the key is an RFC 9651 String.
            .header("Idempotency-Key", "\"" + providerKeyFor(holdId) + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != CREATED && response.statusCode() != REPLAYED) {
        return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
      }
      return switch (json.read(response.body(), ChargeReply.class)) {
        case Result.Success<ChargeReply, JsonCodec.BindingProblem>(ChargeReply reply) ->
            "captured".equals(reply.status())
                ? new PaymentOutcome.Captured(reply.paymentId())
                : new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
        case Result.Error<ChargeReply, JsonCodec.BindingProblem>(
                JsonCodec.BindingProblem problem) ->
            new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
      };
    } catch (HttpTimeoutException timedOut) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.TIMEOUT);
    } catch (ConnectException refused) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.UNREACHABLE);
    } catch (IOException brokenExchange) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
    } catch (InterruptedException interrupted) {
      // Restore the flag so the server's shutdown sees it, and stop: no charge is confirmed.
      Thread.currentThread().interrupt();
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.INTERRUPTED);
    }
  }
}
