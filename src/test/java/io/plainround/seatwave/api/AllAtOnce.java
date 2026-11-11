package io.plainround.seatwave.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Starts many tasks behind one gate and opens it, so they really run at the same moment. */
public final class AllAtOnce {

  private AllAtOnce() {}

  /**
   * Runs the tasks together.
   *
   * @param count how many
   * @param task the task for each index
   * @param <T> what a task returns
   * @return the results, in index order
   * @throws Exception if a task fails
   */
  public static <T> List<T> run(int count, IntFunction<Callable<T>> task) throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<T>> pending = new ArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < count; index++) {
        Callable<T> work = task.apply(index);
        pending.add(
            threads.submit(() -> {
              startGate.await();
              return work.call();
            }));
      }
      // Every thread is waiting at the gate; open it so the requests leave together.
      startGate.countDown();
    }
    List<T> results = new ArrayList<>();
    for (Future<T> result : pending) {
      results.add(result.get());
    }
    return results;
  }
}
