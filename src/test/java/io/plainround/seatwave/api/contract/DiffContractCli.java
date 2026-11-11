package io.plainround.seatwave.api.contract;

import io.plainround.seatwave.api.Contract;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.DiffResult;

/**
 * The {@code diffContract} task: compares a released contract with a candidate and says whether
 * every client of the released one still works. Exit status 0 means compatible, 3 means a breaking
 * change, 2 bad usage.
 */
public final class DiffContractCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_BREAKING = 3;

  private DiffContractCli() {}

  /**
   * Compares two contract files.
   *
   * @param arguments the released file, then the candidate
   */
  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      System.err.println("usage: diffContract <released.yaml> <candidate.yaml>");
      System.exit(EXIT_BAD_USAGE);
    }
    ChangedOpenApi diff = compare(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.printf(Locale.ROOT, "released:  %s%ncandidate: %s%n", arguments[0], arguments[1]);
    System.out.printf(
        Locale.ROOT,
        "operations: %d new, %d removed, %d changed%n",
        diff.getNewEndpoints().size(),
        diff.getMissingEndpoints().size(),
        diff.getChangedOperations().size());
    diff.getMissingEndpoints()
        .forEach(endpoint ->
            System.out.printf(
                Locale.ROOT, "  removed  %s %s%n", endpoint.getMethod(), endpoint.getPathUrl()));
    for (ChangedOperation operation : diff.getChangedOperations()) {
      System.out.printf(
          Locale.ROOT,
          "  %-12s %-6s %-28s request %-12s responses %s%n",
          words(operation.isCoreChanged()),
          operation.getHttpMethod(),
          operation.getPathUrl(),
          words(operation.resultRequestBody()),
          words(operation.resultApiResponses()));
    }
    System.out.println("openapi-diff: " + (diff.isCompatible() ? "compatible" : "breaking"));
    List<String> breaks =
        BreakingChanges.find(
            Contract.text(Path.of(arguments[0])), Contract.text(Path.of(arguments[1])));
    System.out.println(
        "house rules: "
            + (breaks.isEmpty() ? "compatible" : breaks.size() + " breaking change(s)"));
    breaks.forEach(change -> System.out.println("  " + change));
    boolean isCompatible = diff.isCompatible() && breaks.isEmpty();
    System.out.println(
        isCompatible
            ? "verdict: compatible, every released client still works"
            : "verdict: BREAKING, released clients would fail");
    System.exit(isCompatible ? 0 : EXIT_BREAKING);
  }

  /**
   * Compares two contract files.
   *
   * @param released the contract clients use now
   * @param candidate the proposed next contract
   * @return the difference
   */
  public static ChangedOpenApi compare(Path released, Path candidate) {
    return OpenApiCompare.fromContents(Contract.text(released), Contract.text(candidate));
  }

  private static String words(DiffResult result) {
    return result.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
