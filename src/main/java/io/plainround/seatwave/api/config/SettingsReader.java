package io.plainround.seatwave.api.config;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a reader sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,7}\\.[0-9]{2}");

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();

  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file does not exist or cannot be read; the message says which
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (NoSuchFileException missing) {
      // The JDK's message is the bare path, which does not tell a reader what is wrong with it.
      throw new IOException("the settings file " + path + " does not exist", missing);
    } catch (IOException unreadable) {
      throw new IOException(
          "the settings file " + path + " cannot be read: " + unreadable.getMessage(), unreadable);
    }
    return properties;
  }

  /**
   * Records a problem that involves more than one key, so it is reported with the others.
   *
   * @param problem the problem line, naming the keys
   */
  public void reject(String problem) {
    problems.add(Objects.requireNonNull(problem, "problem"));
  }

  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }

  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code Millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }

  /**
   * Reads an instant written in ISO-8601 UTC, for example {@code 2026-11-11T00:00:00Z}.
   *
   * @param key the key
   * @return the instant, or the epoch if the key is missing or invalid
   */
  public Instant instant(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException notAnInstant) {
      problems.add(key + " must be an ISO-8601 UTC instant such as 2026-11-11T00:00:00Z");
      return Instant.EPOCH;
    }
  }

  /**
   * Reads a money amount with exactly two digits after the point, for example {@code 80.00}.
   *
   * @param key the key
   * @return the amount, or zero if the key is missing or invalid
   */
  public BigDecimal amount(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return BigDecimal.ZERO;
    }
    if (!AMOUNT.matcher(text).matches()) {
      problems.add(key + " must be an amount such as 80.00, but was " + text);
      return BigDecimal.ZERO;
    }
    return new BigDecimal(text);
  }

  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @param description what the value must look like, for the problem line
   * @return the value, or an empty string if the key is missing or invalid
   */
  public String matching(String key, Pattern allowed, String description) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return "";
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must be " + description + ", but was " + text);
      return "";
    }
    return text;
  }

  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }

  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
}
