package io.plainround.seatwave.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.HttpStatus;
import org.junit.jupiter.api.Test;

class ReplyTest {

  @Test
  void keepsItsHeadersInTheOrderTheyWereAdded() {
    Reply reply =
        Reply.json(HttpStatus.OK, "[]")
            .withHeader("Deprecation", "@1")
            .withHeader("Sunset", "Thu, 01 Jan 2027 00:00:00 GMT")
            .withHeader("Link", "</v1/events>; rel=\"successor-version\"")
            .withHeader("Retry-After", "1")
            .withHeader("Location", "/v1/holds/x")
            .withHeader("Allow", "GET");

    assertThat(reply.headers().keySet())
        .containsExactly("Deprecation", "Sunset", "Link", "Retry-After", "Location", "Allow");
  }
}
