package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

  private static final int LONGEST_KEY = 64;

  @Test
  void readsTheQuotedStringTheDraftRequires() {
    assertThat(IdempotencyKey.fromHeader("\"8e03978e-40d5-43e8-bc93-6894a57f9324\""))
        .contains(new IdempotencyKey("8e03978e-40d5-43e8-bc93-6894a57f9324"));
  }

  @Test
  void refusesAnUnquotedEmptyTooLongOrEscapedKey() {
    assertThat(IdempotencyKey.fromHeader("8e03978e")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"" + "k".repeat(LONGEST_KEY + 1) + "\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"a\\\"b\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader(null)).isEmpty();
    assertThatThrownBy(() -> new IdempotencyKey("has space"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
