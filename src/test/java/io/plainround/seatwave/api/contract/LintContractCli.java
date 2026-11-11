package io.plainround.seatwave.api.contract;

import io.plainround.seatwave.api.Contract;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code lintContract} task: checks contract files and prints every problem. Exit status 0
 * means every file passed, 3 means a file has problems, 2 bad usage. It runs from the test
 * classpath, where the OpenAPI parser lives, so the service never ships it.
 */
public final class LintContractCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_PROBLEMS = 3;

  private LintContractCli() {}

  /**
   * Checks the files named on the command line.
   *
   * @param arguments one or more contract files
   */
  public static void main(String[] arguments) {
    if (arguments.length == 0) {
      System.err.println("usage: lintContract <openapi.yaml> [more files]");
      System.exit(EXIT_BAD_USAGE);
    }
    boolean isClean = true;
    for (String file : arguments) {
      List<String> problems = ContractRules.check(Contract.text(Path.of(file)));
      System.out.println(
          file
              + ": "
              + (problems.isEmpty()
                  ? "valid, every rule passed"
                  : problems.size() + " problem(s)"));
      problems.forEach(problem -> System.out.println("  " + problem));
      isClean &= problems.isEmpty();
    }
    System.exit(isClean ? 0 : EXIT_PROBLEMS);
  }
}
