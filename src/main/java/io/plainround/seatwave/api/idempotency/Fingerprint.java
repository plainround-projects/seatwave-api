package io.plainround.seatwave.api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A SHA-256 digest of what a request means: the endpoint and the request body re-written in one
 * canonical form. Two bodies that differ only in spacing or field order have the same fingerprint,
 * so a client library that reorders fields is not punished; a different seat or customer is a
 * different fingerprint.
 *
 * @param hex the digest, 64 lowercase hexadecimal characters
 */
public record Fingerprint(String hex) {

  private static final Pattern HEX_SHA_256 = Pattern.compile("[0-9a-f]{64}");

  /** Checks the value. */
  public Fingerprint {
    if (!HEX_SHA_256.matcher(Objects.requireNonNull(hex, "hex")).matches()) {
      throw new IllegalArgumentException("a fingerprint is 64 lowercase hexadecimal characters");
    }
  }

  /**
   * Computes the fingerprint of one request.
   *
   * @param endpoint the operation, for example {@code POST /v1/holds}
   * @param canonicalBody the body, already re-serialized from the bound record
   * @return the fingerprint
   */
  public static Fingerprint of(String endpoint, String canonicalBody) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(canonicalBody, "canonicalBody");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(endpoint.getBytes(StandardCharsets.UTF_8));
      // A separator that cannot occur in an endpoint keeps "a" + "bc" apart from "ab" + "c".
      digest.update((byte) 0);
      digest.update(canonicalBody.getBytes(StandardCharsets.UTF_8));
      return new Fingerprint(HexFormat.of().formatHex(digest.digest()));
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not an input.
      throw new IllegalStateException("SHA-256 is not available", missingAlgorithm);
    }
  }
}
