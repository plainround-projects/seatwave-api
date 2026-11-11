package io.plainround.seatwave.api.contract;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checks a SeatWave contract must pass before any code is written: the OpenAPI parser's own
 * validation (structure, types, references), then the house rules this module teaches. Used by the
 * build's tests and by the {@code lintContract} task.
 */
public final class ContractRules {

  /** The largest page any collection may promise (the contract's Limit maximum). */
  public static final int MAX_PAGE_LIMIT = 500;

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final int FIRST_ERROR_STATUS = 400;

  private ContractRules() {}

  /**
   * Checks one contract document.
   *
   * @param text the document, YAML or JSON
   * @return one line per problem; empty when the document passes
   */
  public static List<String> check(String text) {
    Objects.requireNonNull(text, "text");
    ParseOptions options = new ParseOptions();
    // Inline every reference, so each rule sees the final shape a client sees.
    options.setResolveFully(true);
    SwaggerParseResult parsed = new OpenAPIParser().readContents(text, null, options);
    List<String> problems = new ArrayList<>();
    Optional.ofNullable(parsed.getMessages())
        .ifPresent(messages -> messages.forEach(message -> problems.add("parser: " + message)));
    OpenAPI document = parsed.getOpenAPI();
    if (document == null) {
      problems.add("parser: the document could not be read at all");
      return problems;
    }
    if (document.getOpenapi() == null || !document.getOpenapi().startsWith("3.1.")) {
      problems.add("rule: the document must be OpenAPI 3.1, but says " + document.getOpenapi());
    }
    Set<String> operationIds = new HashSet<>();
    Optional.ofNullable(document.getPaths())
        .ifPresent(paths ->
            paths.forEach((path, item) ->
                item.readOperationsMap()
                    .forEach((method, operation) ->
                        checkOperation(path, method, operation, operationIds, problems))));
    return problems;
  }

  private static void checkOperation(
      String path,
      PathItem.HttpMethod method,
      Operation operation,
      Set<String> operationIds,
      List<String> problems) {
    String name = method + " " + path;
    if (operation.getOperationId() == null || !operationIds.add(operation.getOperationId())) {
      problems.add("rule: " + name + " needs a unique operationId");
    }
    if (Boolean.TRUE.equals(operation.getDeprecated())) {
      // Version 0 keeps its old shapes on purpose; the rules below are for what is current.
      return;
    }
    Map<String, ApiResponse> responses =
        Optional.ofNullable(operation.getResponses()).map(Map::copyOf).orElse(Map.of());
    responses.forEach((status, response) -> {
      boolean isError =
          status.matches("[0-9]{3}") && Integer.parseInt(status) >= FIRST_ERROR_STATUS;
      if (isError && !isProblem(response.getContent())) {
        problems.add("rule: " + name + " " + status + " must be application/problem+json Problem");
      }
    });
    if (method == PathItem.HttpMethod.POST
        && operation.getRequestBody() != null
        && !requiresHeader(operation, "Idempotency-Key")) {
      problems.add("rule: " + name + " creates something, so it must require Idempotency-Key");
    }
    if (method == PathItem.HttpMethod.GET && returnsPage(responses)) {
      checkPagination(name, operation, problems);
    }
  }

  private static void checkPagination(String name, Operation operation, List<String> problems) {
    Optional<Parameter> limit = parameter(operation, "limit");
    BigDecimal maximum =
        limit.map(Parameter::getSchema).map(schema -> schema.getMaximum()).orElse(BigDecimal.ZERO);
    if (limit.isEmpty() || maximum.signum() <= 0 || maximum.intValue() > MAX_PAGE_LIMIT) {
      problems.add("rule: " + name + " returns a page, so limit needs a maximum of at most 500");
    }
    if (parameter(operation, "cursor").isEmpty()) {
      problems.add("rule: " + name + " returns a page, so it needs a cursor parameter");
    }
  }

  private static boolean returnsPage(Map<String, ApiResponse> responses) {
    return Optional.ofNullable(responses.get("200"))
        .map(ApiResponse::getContent)
        .map(content -> content.get("application/json"))
        .map(mediaType -> mediaType.getSchema())
        .map(
            schema -> schema.getProperties() != null && schema.getProperties().containsKey("items"))
        .orElse(false);
  }

  private static boolean isProblem(Content content) {
    if (content == null || content.size() != 1 || !content.containsKey(PROBLEM_JSON)) {
      return false;
    }
    Schema<?> schema = content.get(PROBLEM_JSON).getSchema();
    List<String> required =
        schema == null ? List.of() : Objects.requireNonNullElse(schema.getRequired(), List.of());
    return required.containsAll(List.of("type", "title", "status"));
  }

  private static boolean requiresHeader(Operation operation, String header) {
    return Objects.requireNonNullElse(operation.getParameters(), List.<Parameter>of()).stream()
        .anyMatch(parameter ->
            "header".equals(parameter.getIn())
                && header.equalsIgnoreCase(parameter.getName())
                && Boolean.TRUE.equals(parameter.getRequired()));
  }

  private static Optional<Parameter> parameter(Operation operation, String name) {
    return Objects.requireNonNullElse(operation.getParameters(), List.<Parameter>of()).stream()
        .filter(parameter -> "query".equals(parameter.getIn()) && name.equals(parameter.getName()))
        .findFirst();
  }
}
