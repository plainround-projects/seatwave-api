package io.plainround.seatwave.api;

/** The status codes the tests expect, by their RFC 9110 names. */
public final class HttpStatus {

  /** 200 OK. */
  public static final int OK = 200;

  /** 201 Created. */
  public static final int CREATED = 201;

  /** 204 No Content. */
  public static final int NO_CONTENT = 204;

  /** 400 Bad Request. */
  public static final int BAD_REQUEST = 400;

  /** 404 Not Found. */
  public static final int NOT_FOUND = 404;

  /** 405 Method Not Allowed. */
  public static final int METHOD_NOT_ALLOWED = 405;

  /** 409 Conflict. */
  public static final int CONFLICT = 409;

  /** 413 Content Too Large. */
  public static final int CONTENT_TOO_LARGE = 413;

  /** 415 Unsupported Media Type. */
  public static final int UNSUPPORTED_MEDIA_TYPE = 415;

  /** 422 Unprocessable Content. */
  public static final int UNPROCESSABLE_CONTENT = 422;

  /** 503 Service Unavailable. */
  public static final int SERVICE_UNAVAILABLE = 503;

  private HttpStatus() {}
}
