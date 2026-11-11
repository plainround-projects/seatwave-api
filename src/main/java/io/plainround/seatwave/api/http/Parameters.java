package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a query string or a form body into names and values, against an allow-list of names. A name
 * the endpoint does not take, or a name sent twice, is an error: silently picking one of two values
 * is how two layers end up disagreeing about one request.
 */
final class Parameters {

  private final Map<String, String> values;

  private Parameters(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  static Result<Parameters, List<FieldError>> parse(String encoded, Set<String> allowedNames) {
    List<FieldError> errors = new ArrayList<>();
    Parameters parameters = read(encoded, allowedNames, errors);
    return errors.isEmpty() ? Result.success(parameters) : Result.error(List.copyOf(errors));
  }

  /**
   * Reads every pair, adds one error per bad pair to {@code errors}, and keeps the good ones, so
   * the caller can still check their values and report every problem in one reply.
   *
   * @return the allowed names with their first value
   */
  static Parameters read(String encoded, Set<String> allowedNames, List<FieldError> errors) {
    Map<String, String> values = new LinkedHashMap<>();
    if (encoded.isEmpty()) {
      return new Parameters(values);
    }
    for (String pair : encoded.split("&", -1)) {
      int separator = pair.indexOf('=');
      if (separator <= 0) {
        errors.add(new FieldError(pair, "is not a name=value pair"));
        continue;
      }
      try {
        String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
        String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
        if (!allowedNames.contains(name)) {
          errors.add(new FieldError(name, "is not a parameter of this endpoint"));
        } else if (values.putIfAbsent(name, value) != null) {
          errors.add(new FieldError(name, "is given more than once"));
        }
      } catch (IllegalArgumentException badEncoding) {
        // URLDecoder rejects a broken percent escape; that is the client's error, reported.
        errors.add(new FieldError(pair, "is not valid percent-encoding"));
      }
    }
    return new Parameters(values);
  }

  Optional<String> get(String name) {
    return Optional.ofNullable(values.get(name));
  }

  /**
   * Reads an optional whole number in a range.
   *
   * @return the value, the fallback when the parameter is absent, or empty when it is invalid (the
   *     error is added to {@code errors})
   */
  Optional<Integer> intBetween(
      String name, int minimum, int maximum, int fallback, List<FieldError> errors) {
    Optional<String> text = get(name);
    if (text.isEmpty()) {
      return Optional.of(fallback);
    }
    if (Pattern.matches("[0-9]{1,9}", text.get())) {
      int value = Integer.parseInt(text.get());
      if (value >= minimum && value <= maximum) {
        return Optional.of(value);
      }
    }
    errors.add(new FieldError(name, "must be a whole number from " + minimum + " to " + maximum));
    return Optional.empty();
  }
}
