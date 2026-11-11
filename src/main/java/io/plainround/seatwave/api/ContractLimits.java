package io.plainround.seatwave.api;

import java.time.Duration;

/**
 * The numbers the OpenAPI document publishes. They are constants, not settings, because a client
 * reads them in the contract: changing one is a contract change, made in the document first.
 */
public final class ContractLimits {

  /** The largest request body, in bytes ("TooLarge" in the contract); Module 1's limit. */
  public static final int MAX_BODY_BYTES = 1_024;

  /** Items per page when the client sends no limit (the "Limit" parameter's default). */
  public static final int DEFAULT_PAGE_LIMIT = 100;

  /** The most items one page may hold (the "Limit" parameter's maximum). */
  public static final int MAX_PAGE_LIMIT = 500;

  /** How long a hold lasts (the summary of POST /v1/holds); Module 1's 300 seconds. */
  public static final Duration HOLD_DURATION = Duration.ofSeconds(300);

  /** How long an Idempotency-Key is remembered after its first use (the document's rules). */
  public static final Duration KEY_RETENTION = Duration.ofHours(24);

  private ContractLimits() {}
}
