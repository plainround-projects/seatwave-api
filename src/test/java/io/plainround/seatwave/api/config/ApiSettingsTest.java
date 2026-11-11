package io.plainround.seatwave.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.TestApi;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ApiSettingsTest {

  private static final int UNUSED_PROVIDER_PORT = 18_100;
  private static final int MORE_SECTIONS_THAN_A_TO_T = 21;

  @Test
  void readsTheShippedSettingsFile() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));

    Result<ApiSettings, List<String>> settings = ApiSettings.from(new SettingsReader(properties));

    assertThat(settings).isInstanceOf(Result.Success.class);
  }

  @Test
  void listsEveryMissingKeyInOneRun() {
    Result<ApiSettings, List<String>> settings =
        ApiSettings.from(new SettingsReader(new Properties()));

    assertThat(settings)
        .isInstanceOfSatisfying(
            Result.Error.class,
            error ->
                assertThat(error.toString())
                    .contains("http.port is missing")
                    .contains("payment.url is missing")
                    .contains("version0.sunsetAt is missing"));
  }

  @Test
  void refusesAProviderOutsideTheLoopbackAddressAndBadValues() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("payment.url", "http://payments.example:443");
    properties.setProperty("booking.seatPrice", "80");
    properties.setProperty("version0.sunsetAt", "next spring");
    properties.setProperty("http.port", "80");

    Result<ApiSettings, List<String>> settings = ApiSettings.from(new SettingsReader(properties));

    assertThat(settings.toString())
        .contains("payment.url must be http://127.0.0.1:<port>")
        .contains("booking.seatPrice must be an amount such as 80.00")
        .contains("version0.sunsetAt must be an ISO-8601 UTC instant")
        .contains("http.port must be from 1024 to 65535");
  }

  @Test
  void refusesALeaseThatDoesNotOutlastThePaymentCall() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("payment.leaseMillis", "1000");

    assertThat(ApiSettings.from(new SettingsReader(properties)).toString())
        .contains("payment.leaseMillis must be longer than the connect and request timeouts");
  }

  @Test
  void cannotBeBuiltWithImpossibleValues() {
    ApiSettings valid = TestApi.settings(new ApiSettings.Seed(1, 1, 1), 1, UNUSED_PROVIDER_PORT);

    assertThatThrownBy(() -> new ApiSettings.Seed(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApiSettings.Seed(1, MORE_SECTIONS_THAN_A_TO_T, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ApiSettings(
                valid.httpPort(),
                0,
                valid.databaseUrl(),
                valid.seed(),
                valid.payment(),
                valid.seatPrice(),
                valid.currency(),
                valid.retryAfter(),
                valid.version0()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ApiSettings.Version0(valid.version0().sunsetAt(), valid.version0().deprecatedAt()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
