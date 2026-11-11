package io.plainround.seatwave.api.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;

/** Every way a body can fail to bind, each reported at the JSON pointer of what is wrong. */
class JsonCodecTest {

  private final JsonCodec json = new JsonCodec();

  /** The shape of a hold request: a whole number and two strings. */
  record HoldBody(Long eventId, String seat, String customer) {}

  @Test
  void refusesANumberOrABooleanSentForAStringField() {
    assertThat(problemOf("{\"eventId\":1,\"seat\":\"D-1\",\"customer\":5}"))
        .isEqualTo(new JsonCodec.BindingProblem("/customer", "has the wrong type"));
    assertThat(problemOf("{\"eventId\":1,\"seat\":true,\"customer\":\"fan_1\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/seat", "has the wrong type"));
    assertThat(problemOf("{\"eventId\":1,\"seat\":1.5,\"customer\":\"fan_1\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/seat", "has the wrong type"));
  }

  @Test
  void reportsAProblemWithTheWholeBodyAtTheEmptyPointerAndSaysWhatIsWrong() {
    assertThat(problemOf(""))
        .isEqualTo(new JsonCodec.BindingProblem("", "the body is empty; send a JSON object"));
    assertThat(problemOf("[1]"))
        .isEqualTo(new JsonCodec.BindingProblem("", "the body must be a JSON object"));
    assertThat(problemOf("{\"eventId\":1,\"seat\":\"A-7\",\"customer\":\"fan_7\"} {}"))
        .isEqualTo(new JsonCodec.BindingProblem("", "the body has data after the JSON object"));
  }

  @Test
  void reportsANumberTooLargeForItsFieldAtTheFieldsPointer() {
    assertThat(problemOf("{\"eventId\":99999999999999999999,\"seat\":\"D-1\",\"customer\":\"x\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/eventId", "is out of range for this field"));
  }

  @Test
  void escapesASlashAndATildeInAnUnknownFieldName() {
    assertThat(problemOf("{\"a/b~c\":1}"))
        .isEqualTo(new JsonCodec.BindingProblem("/a~1b~0c", "is not a field of this request"));
  }

  @Test
  void stillCallsBrokenSyntaxInvalidJson() {
    assertThat(problemOf("{\"eventId\":1,"))
        .isEqualTo(new JsonCodec.BindingProblem("", "the body is not valid JSON"));
  }

  private JsonCodec.BindingProblem problemOf(String body) {
    return switch (json.read(body, HoldBody.class)) {
      case Result.Error<HoldBody, JsonCodec.BindingProblem>(JsonCodec.BindingProblem problem) ->
          problem;
      case Result.Success<HoldBody, JsonCodec.BindingProblem>(HoldBody bound) ->
          throw new AssertionError("the body bound to " + bound);
    };
  }
}
