package io.plainround.seatwave.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The OpenAPI document as a judge: it says whether a reply (or a request) is one the contract
 * allows. The document is read from the repository, the one copy clients read too.
 *
 * <p>Thread-safe: the validator is built once and only read.
 */
public final class Contract {

  /** The path of the contract, set by the build for the test task. */
  public static final Path DOCUMENT =
      Path.of(System.getProperty("seatwave.contract", "openapi/seatwave-api.yaml"));

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForInlineApiSpecification(text(DOCUMENT)).build();

  private Contract() {}

  /**
   * Reads a contract file.
   *
   * @param path the file
   * @return its text
   */
  public static String text(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Judges a reply.
   *
   * @param method the request's method
   * @param path the request's path, without the query
   * @param status the reply's status
   * @param headers the reply's headers
   * @param body the reply's body
   * @return the validator's report; empty of errors when the contract allows the reply
   */
  public static ValidationReport judgeReply(
      String method, String path, int status, Map<String, String> headers, String body) {
    SimpleResponse.Builder response = SimpleResponse.Builder.status(status);
    headers.forEach(response::withHeader);
    if (!body.isEmpty()) {
      response.withBody(body);
    }
    return VALIDATOR.validateResponse(
        path, Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), response.build());
  }

  /**
   * Judges a request.
   *
   * @param method the method
   * @param path the path, without the query
   * @param query the query parameters
   * @param headers the headers
   * @param body the body, or an empty string
   * @return the validator's report; empty of errors when the contract allows the request
   */
  public static ValidationReport judgeRequest(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      String body) {
    SimpleRequest.Builder request =
        new SimpleRequest.Builder(Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), path);
    query.forEach(request::withQueryParam);
    headers.forEach(request::withHeader);
    if (!body.isEmpty()) {
      request.withBody(body);
    }
    return VALIDATOR.validateRequest(request.build());
  }
}
