package io.plainround.seatwave.api.contract;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The house breaking-change check, next to openapi-diff: a candidate contract breaks a released one
 * when a client written against the released one could fail. The rules are the ones Module 7
 * teaches, small enough to read:
 *
 * <ul>
 *   <li>no operation and no documented status code disappears;
 *   <li>no response field disappears, changes type or stops being required, and no response gains
 *       an allowed value (a client that switches over the known values has no branch for it);
 *   <li>no request field disappears (a client sends it, and unknown fields are refused) or changes
 *       type, no new request field or parameter is required, and no allowed value is removed.
 * </ul>
 *
 * <p>A removed response value is not on the list: a client never fails because a value stops
 * arriving. Each media type is compared with the same media type of the candidate, by name.
 */
public final class BreakingChanges {

  private BreakingChanges() {}

  /**
   * Lists every breaking change.
   *
   * @param released the contract clients use now
   * @param candidate the proposed next one
   * @return one line per breaking change; empty when every released client still works
   */
  public static List<String> find(String released, String candidate) {
    OpenAPI before = parse(released);
    OpenAPI after = parse(candidate);
    List<String> breaks = new ArrayList<>();
    before
        .getPaths()
        .forEach((path, item) ->
            item.readOperationsMap()
                .forEach((method, operation) -> {
                  Optional<Operation> next = operation(after, path, method);
                  if (next.isEmpty()) {
                    breaks.add(method + " " + path + ": the operation was removed");
                  } else {
                    compare(method + " " + path, operation, next.get(), breaks);
                  }
                }));
    return breaks;
  }

  private static void compare(String name, Operation before, Operation after, List<String> breaks) {
    for (Parameter parameter : listOf(after.getParameters())) {
      boolean wasThere =
          listOf(before.getParameters()).stream()
              .anyMatch(old -> old.getName().equals(parameter.getName()));
      if (Boolean.TRUE.equals(parameter.getRequired()) && !wasThere) {
        breaks.add(name + ": new required parameter " + parameter.getName());
      }
    }
    for (Parameter parameter : listOf(before.getParameters())) {
      listOf(after.getParameters()).stream()
          .filter(next -> next.getName().equals(parameter.getName()))
          .findFirst()
          .ifPresent(next ->
              compareRequest(
                  name + " parameter " + parameter.getName(),
                  parameter.getSchema(),
                  next.getSchema(),
                  breaks));
    }
    if (before.getRequestBody() != null && after.getRequestBody() != null) {
      compareContent(
          before.getRequestBody().getContent(),
          after.getRequestBody().getContent(),
          (oldSchema, newSchema) ->
              compareRequest(name + " request", oldSchema, newSchema, breaks));
    }
    Map<String, ApiResponse> newResponses = mapOf(after.getResponses());
    mapOf(before.getResponses())
        .forEach((status, response) -> {
          ApiResponse next = newResponses.get(status);
          if (next == null) {
            breaks.add(name + ": response " + status + " was removed");
            return;
          }
          compareContent(
              response.getContent(),
              next.getContent(),
              (oldSchema, newSchema) ->
                  compareResponse(name + " " + status, oldSchema, newSchema, breaks));
        });
  }

  private static void compareResponse(
      String where, Schema<?> before, Schema<?> after, List<String> breaks) {
    if (!Objects.equals(typeOf(before), typeOf(after))) {
      breaks.add(where + ": type changed from " + typeOf(before) + " to " + typeOf(after));
      return;
    }
    for (String required : listOf(before.getRequired())) {
      if (!listOf(after.getRequired()).contains(required)) {
        breaks.add(where + ": field " + required + " is no longer always present");
      }
    }
    if (before.getEnum() != null) {
      for (Object allowed : listOf(after.getEnum())) {
        if (!before.getEnum().contains(allowed)) {
          breaks.add(where + ": the value " + allowed + " is new, so clients may not know it");
        }
      }
    }
    mapOf(before.getProperties())
        .forEach((field, schema) -> {
          Schema<?> next = mapOf(after.getProperties()).get(field);
          if (next == null) {
            breaks.add(where + ": field " + field + " was removed");
          } else {
            compareResponse(where + "." + field, schema, next, breaks);
          }
        });
    if (before.getItems() != null && after.getItems() != null) {
      compareResponse(where + "[]", before.getItems(), after.getItems(), breaks);
    }
  }

  private static void compareRequest(
      String where, Schema<?> before, Schema<?> after, List<String> breaks) {
    if (!Objects.equals(typeOf(before), typeOf(after))) {
      breaks.add(where + ": type changed from " + typeOf(before) + " to " + typeOf(after));
      return;
    }
    for (String required : listOf(after.getRequired())) {
      if (!listOf(before.getRequired()).contains(required)) {
        breaks.add(where + ": field " + required + " is newly required");
      }
    }
    for (Object allowed : listOf(before.getEnum())) {
      if (after.getEnum() != null && !after.getEnum().contains(allowed)) {
        breaks.add(where + ": the value " + allowed + " is no longer allowed");
      }
    }
    mapOf(before.getProperties())
        .forEach((field, schema) -> {
          Schema<?> next = mapOf(after.getProperties()).get(field);
          if (next == null) {
            breaks.add(where + ": field " + field + " was removed, so clients that send it fail");
          } else {
            compareRequest(where + "." + field, schema, next, breaks);
          }
        });
  }

  private static Optional<Operation> operation(
      OpenAPI document, String path, PathItem.HttpMethod method) {
    return Optional.ofNullable(document.getPaths().get(path))
        .map(item -> item.readOperationsMap().get(method));
  }

  private static void compareContent(
      Content before, Content after, BiConsumer<Schema<?>, Schema<?>> comparison) {
    // A client reads the media type it asked for, so each one is compared with its namesake; a
    // reply with two media types must not be judged by whichever the document lists first.
    mapOf(before)
        .forEach((mediaType, released) -> {
          MediaType candidate = mapOf(after).get(mediaType);
          if (candidate != null && released.getSchema() != null && candidate.getSchema() != null) {
            comparison.accept(released.getSchema(), candidate.getSchema());
          }
        });
  }

  private static String typeOf(Schema<?> schema) {
    // OpenAPI 3.1 writes the type as a set ("types"); 3.0 as one string ("type").
    return schema.getTypes() != null
        ? String.join(",", schema.getTypes())
        : String.valueOf(schema.getType());
  }

  private static OpenAPI parse(String text) {
    ParseOptions options = new ParseOptions();
    options.setResolveFully(true);
    OpenAPI document = new OpenAPIParser().readContents(text, null, options).getOpenAPI();
    if (document == null) {
      throw new IllegalArgumentException("not an OpenAPI document");
    }
    return document;
  }

  private static <T> List<T> listOf(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static <V> Map<String, V> mapOf(Map<String, V> values) {
    return values == null ? Map.of() : values;
  }
}
