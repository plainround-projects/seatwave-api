package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

  @Test
  void refusesARemovedStatusARemovedRequestFieldAndANewRequiredParameter() {
    String released = Contract.text(RELEASED);
    String candidate =
        replaceOnce(
            replaceOnce(
                replaceOnce(
                    replaceOnce(
                        released,
                        "        '422':\n"
                            + "          $ref: '#/components/responses/KeyReused'\n"
                            + "        '503':\n"
                            + "          $ref: '#/components/responses/Unavailable'\n"
                            + "  /v1/holds/{holdId}:\n",
                        "        '503':\n"
                            + "          $ref: '#/components/responses/Unavailable'\n"
                            + "  /v1/holds/{holdId}:\n"),
                    "    HoldRequest:\n"
                        + "      type: object\n"
                        + "      additionalProperties: false\n"
                        + "      required: [eventId, seat, customer]\n",
                    "    HoldRequest:\n"
                        + "      type: object\n"
                        + "      additionalProperties: false\n"
                        + "      required: [eventId, seat]\n"),
                "        customer:\n"
                    + "          type: string\n"
                    + "          pattern: '^[A-Za-z0-9_-]{1,64}$'\n",
                ""),
            "      summary: Read a hold and its state.\n"
                + "      parameters:\n"
                + "        - $ref: '#/components/parameters/HoldId'\n",
            "      summary: Read a hold and its state.\n"
                + "      parameters:\n"
                + "        - $ref: '#/components/parameters/HoldId'\n"
                + "        - name: X-Client\n"
                + "          in: header\n"
                + "          required: true\n"
                + "          schema:\n"
                + "            type: string\n");

    assertThat(BreakingChanges.find(released, candidate))
        .contains(
            "POST /v1/holds: response 422 was removed",
            "POST /v1/holds request: field customer was removed, so clients that send it fail",
            "GET /v1/holds/{holdId}: new required parameter X-Client");
  }

  @Test
  void refusesANewResponseValueButAcceptsARemovedOne() {
    String released = Contract.text(RELEASED);
    String holdStates = "enum: [held, confirming, confirmed, cancelled, expired]";
    String added =
        replaceOnce(
            released, holdStates, "enum: [held, confirming, confirmed, cancelled, expired, sold]");
    String removed =
        replaceOnce(released, holdStates, "enum: [held, confirming, confirmed, cancelled]");

    assertThat(BreakingChanges.find(released, added))
        .anySatisfy(change -> assertThat(change).contains(".state: the value sold is new"));
    // A client never fails because a value stops arriving; openapi-diff agrees.
    assertThat(BreakingChanges.find(released, removed)).isEmpty();
    assertThat(DiffContractCli.compare(RELEASED, writeTo(removed)).isCompatible()).isTrue();
  }

  @Test
  void comparesEachMediaTypeWithTheSameOneByName() {
    String released = Contract.text(RELEASED);
    // The candidate lists an unchanged media type first and drops fields from application/json.
    String candidate =
        replaceOnce(
            released,
            "          content:\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                $ref: '#/components/schemas/Hold'\n"
                + "        '400':\n"
                + "          $ref: '#/components/responses/BadRequest'\n"
                + "        '404':\n"
                + "          $ref: '#/components/responses/NotFound'\n"
                + "        '409':\n",
            "          content:\n"
                + "            application/vnd.seatwave+json:\n"
                + "              schema:\n"
                + "                $ref: '#/components/schemas/Hold'\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                type: object\n"
                + "                required: [holdId]\n"
                + "                properties:\n"
                + "                  holdId:\n"
                + "                    type: string\n"
                + "        '400':\n"
                + "          $ref: '#/components/responses/BadRequest'\n"
                + "        '404':\n"
                + "          $ref: '#/components/responses/NotFound'\n"
                + "        '409':\n");

    assertThat(BreakingChanges.find(released, candidate))
        .contains("POST /v1/holds 201: field eventId is no longer always present");
  }

  private static String replaceOnce(String text, String target, String replacement) {
    // A test edit that matches nothing would test the released document against itself.
    assertThat(text).containsOnlyOnce(target);
    return text.replace(target, replacement);
  }

  private static Path writeTo(String document) {
    try {
      Path file = Files.createTempFile("seatwave-candidate-", ".yaml");
      file.toFile().deleteOnExit();
      return Files.writeString(file, document, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
