package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The contract is checked before the code: if the document is invalid, the build fails here. */
class OpenApiDocumentTest {

  // 418 is a real status code (RFC 9110 reserves it) that the contract never mentions.
  private static final int UNDOCUMENTED_STATUS = 418;

  private final String document = Contract.text(Contract.DOCUMENT);

  @Test
  void passesTheParserAndEveryHouseRule() {
    assertThat(ContractRules.check(document)).isEmpty();
  }

  @Test
  void publishesTheSameLimitsTheCodeEnforces() {
    // The code's constants and the document's numbers must agree, or one of them lies.
    assertThat(document)
        .contains("maximum: " + ContractLimits.MAX_PAGE_LIMIT)
        .contains("default: " + ContractLimits.DEFAULT_PAGE_LIMIT)
        .contains("Hold one seat for " + ContractLimits.HOLD_DURATION.toSeconds() + " seconds")
        .contains("kept for " + ContractLimits.KEY_RETENTION.toHours() + " hours");
    assertThat(document)
        .contains(String.format(Locale.ROOT, "over %,d bytes", ContractLimits.MAX_BODY_BYTES));
    assertThat(ContractRules.MAX_PAGE_LIMIT).isEqualTo(ContractLimits.MAX_PAGE_LIMIT);
  }

  @Test
  void catchesAReplyThatBreaksTheContract() {
    // What a regression would look like: the build fails on each of these.
    String withoutStart = "{\"items\":[{\"eventId\":1,\"name\":\"Arena Night\"}]}";
    String plainError = "{\"error\":\"seat not available\"}";

    assertThat(
            Contract.judgeReply(
                    "GET",
                    "/v1/events",
                    HttpStatus.OK,
                    Map.of("Content-Type", "application/json"),
                    withoutStart)
                .getMessages())
        .anySatisfy(message -> assertThat(message.getMessage()).contains("startsAt"));
    assertThat(
            Contract.judgeReply(
                    "POST",
                    "/v1/holds",
                    HttpStatus.CONFLICT,
                    Map.of("Content-Type", "application/json"),
                    plainError)
                .hasErrors())
        .isTrue();
    assertThat(
            Contract.judgeReply(
                    "GET",
                    "/v1/events",
                    UNDOCUMENTED_STATUS,
                    Map.of("Content-Type", "application/json"),
                    "{}")
                .getMessages())
        .anySatisfy(message -> assertThat(message.getMessage()).contains("418 not defined"));
  }

  @Test
  void findsTheMistakesInABrokenHoldsEntry() {
    String broken = Contract.text(Path.of("openapi/examples/holds-entry-broken.yaml"));

    assertThat(ContractRules.check(broken))
        .anySatisfy(problem -> assertThat(problem).contains("Idempotency-Key"))
        .anySatisfy(problem -> assertThat(problem).contains("409 must be application/problem+json"))
        .anySatisfy(problem -> assertThat(problem).startsWith("parser:"));
  }

  @Test
  void acceptsTheLabSolutionForTheHoldsEntry() {
    assertThat(ContractRules.check(Contract.text(Path.of("openapi/examples/holds-entry.yaml"))))
        .isEmpty();
  }
}
