package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** No change to the contract may break a client of the released one: the build checks it. */
class BreakingChangeTest {

  private static final Path RELEASED =
      Path.of(
          System.getProperty(
              "seatwave.releasedContract", "openapi/released/seatwave-api-1.0.0.yaml"));

  @Test
  void keepsEveryClientOfTheReleasedContractWorking() {
    assertThat(DiffContractCli.compare(RELEASED, Contract.DOCUMENT).isCompatible()).isTrue();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(Contract.DOCUMENT)))
        .isEmpty();
  }

  @Test
  void acceptsAnAdditiveChange() {
    Path additive = Path.of("openapi/examples/seatwave-api-1.1.0-additive.yaml");

    assertThat(DiffContractCli.compare(RELEASED, additive).isCompatible()).isTrue();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(additive))).isEmpty();
  }

  @Test
  void refusesARenamedResponseFieldAndANewRequiredRequestField() {
    Path breaking = Path.of("openapi/examples/seatwave-api-1.1.0-breaking.yaml");

    assertThat(DiffContractCli.compare(RELEASED, breaking).isCompatible()).isFalse();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(breaking)))
        .anySatisfy(change -> assertThat(change).contains("field state was removed"))
        .anySatisfy(change -> assertThat(change).contains("field channel is newly required"));
  }

  @Test
  void refusesARemovedOperationAndARemovedAllowedValue() {
    String released = Contract.text(RELEASED);
    String candidate =
        released
            .replace("  /v1/bookings/{bookingId}:", "  /v1/bookings-by-id/{bookingId}:")
            .replace("enum: [available, held, sold]", "enum: [available, sold]");

    assertThat(BreakingChanges.find(released, candidate))
        .anySatisfy(change ->
            assertThat(change).contains("GET /v1/bookings/{bookingId}: the operation was removed"))
        .anySatisfy(change -> assertThat(change).contains("the value held is no longer allowed"));
  }
}
