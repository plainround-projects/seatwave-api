package io.plainround.seatwave.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.load.PageWalk;
import org.junit.jupiter.api.Test;

/**
 * The pagination consistency test: while fans hold seats and the venue adds seats between pages,
 * the cursor walk returns every seat that stayed available exactly once; an offset walk does not.
 */
class PaginationConsistencyTest {

  private static final int SECTIONS = 5;
  private static final int SEATS_PER_SECTION = 200;
  private static final int ALL_SEATS = SECTIONS * SEATS_PER_SECTION;
  private static final int CHANGES_PER_PAGE = 10;
  private static final int MAX_IN_FLIGHT = 64;
  private static final int UNUSED_PROVIDER_PORT = 18_099;
  // A seat is about 50 bytes of JSON, for example {"seat":"A-17","section":"A","state":"held"}.
  private static final long MAX_BYTES_PER_SEAT = 60;
  // Five sections of 200 seats: 1,000 seats, 20 pages of 50.
  private static final ApiSettings.Seed SEED = new ApiSettings.Seed(1, SECTIONS, SEATS_PER_SECTION);
  private static final int PAGE_SIZE = 50;

  @Test
  void neitherSkipsNorRepeatsASeatWhileFansHoldSeats() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, CHANGES_PER_PAGE, 0));

    assertThat(report.cursor().skipped()).isZero();
    assertThat(report.cursor().repeated()).isZero();
    // The same changes make the offset walk skip seats: each hold behind it moves the rest back.
    assertThat(report.offset().skipped()).isPositive();
  }

  @Test
  void neitherSkipsNorRepeatsASeatWhileTheVenueAddsSeats() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, 0, CHANGES_PER_PAGE));

    assertThat(report.cursor().skipped()).isZero();
    assertThat(report.cursor().repeated()).isZero();
    // Each seat added behind an offset walk pushes one it has seen onto its next page.
    assertThat(report.offset().repeated()).isPositive();
  }

  @Test
  void keepsEveryPageWithinTheLimit() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, 0, 0));

    assertThat(report.cursor().pages()).isEqualTo(ALL_SEATS / PAGE_SIZE);
    assertThat(report.cursor().returned()).isEqualTo(ALL_SEATS);
    assertThat(report.largestPageBytes()).isLessThan(PAGE_SIZE * MAX_BYTES_PER_SEAT);
  }

  private static PageWalk.Report walk(PageWalk.Changes changes) throws Exception {
    // No confirm runs in a walk, so the provider's port is never called.
    return new PageWalk(TestApi.settings(SEED, MAX_IN_FLIGHT, UNUSED_PROVIDER_PORT), changes).run();
  }
}
