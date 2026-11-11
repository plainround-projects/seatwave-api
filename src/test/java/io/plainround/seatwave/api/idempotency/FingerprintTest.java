package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FingerprintTest {

  // SHA-256 is 32 bytes, two hexadecimal characters each.
  private static final int SHA_256_HEX_LENGTH = 64;

  @Test
  void isTheSameForTheSameRequestAndDifferentOtherwise() {
    Fingerprint first = Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}");

    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}")).isEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-2\"}")).isNotEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/bookings", "{\"seat\":\"A-1\"}")).isNotEqualTo(first);
    assertThat(first.hex()).hasSize(SHA_256_HEX_LENGTH);
  }

  @Test
  void keepsTheEndpointAndTheBodyApart() {
    assertThat(Fingerprint.of("ab", "c")).isNotEqualTo(Fingerprint.of("a", "bc"));
  }

  @Test
  void cannotHoldSomethingThatIsNotADigest() {
    assertThatThrownBy(() -> new Fingerprint("abc")).isInstanceOf(IllegalArgumentException.class);
  }
}
