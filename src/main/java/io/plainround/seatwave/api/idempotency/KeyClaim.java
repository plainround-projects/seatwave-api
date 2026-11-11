package io.plainround.seatwave.api.idempotency;

import java.util.Objects;

/**
 * One request's claim on a key: which endpoint, which key, and what the request means.
 *
 * @param endpoint the operation, for example {@code POST /v1/holds}; keys are scoped to it
 * @param key the client's key
 * @param fingerprint the digest of the request
 */
public record KeyClaim(String endpoint, IdempotencyKey key, Fingerprint fingerprint) {

  /** Checks the fields. */
  public KeyClaim {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
  }
}
