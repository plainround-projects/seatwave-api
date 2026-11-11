package io.plainround.seatwave.api.load;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * A small HTTP client for the load tools: one JDK {@link HttpClient}, created once, talking to
 * 127.0.0.1 only.
 *
 * <p>Thread-safe: {@link HttpClient} is; many virtual threads send through one instance.
 */
final class ApiClient implements AutoCloseable {

  private final URI base;
  private final Duration timeout;
  private final HttpClient httpClient;

  ApiClient(int port, Duration timeout) {
    this.base = URI.create("http://127.0.0.1:" + port);
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  /**
   * One reply.
   *
   * @param status the status code
   * @param body the body
   * @param elapsed the time from send to the last byte
   */
  record Answer(int status, String body, Duration elapsed) {}

  Answer get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).GET().build());
  }

  Answer postJson(String path, String key, String json) throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "\"" + key + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build());
  }

  @Override
  public void close() {
    httpClient.close();
  }

  private Answer send(HttpRequest request) throws IOException, InterruptedException {
    long startNanos = System.nanoTime();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Answer(
        response.statusCode(), response.body(), Duration.ofNanos(System.nanoTime() - startNanos));
  }
}
