package io.plainround.seatwave.api.idempotency;

/** What the key store found when a request claimed its key. */
public sealed interface ClaimResult {

  /** The key was new: this request owns it and must do the work. */
  record Claimed() implements ClaimResult {}

  /**
   * The key belongs to an earlier attempt of this same request that did not finish (its process
   * stopped, or its payment call failed). This request may carry the work on; the state of the
   * resource decides whether it can.
   */
  record Resumed() implements ClaimResult {}

  /**
   * The same request already completed: send its response again and do nothing.
   *
   * @param reply the first response
   */
  record Replay(StoredReply reply) implements ClaimResult {}

  /** The key was used before with a different request: refuse (422 in the contract). */
  record BodyMismatch() implements ClaimResult {}
}
