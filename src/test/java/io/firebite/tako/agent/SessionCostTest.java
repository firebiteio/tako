package io.firebite.tako.agent;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionCostTest {

  @Test
  public void aResumeThatRestoredNothingAddsItsWholeCount() {
    SessionCost thread = new SessionCost(200, 1000, 500, 9000, 800, null);
    SessionCost reported = new SessionCost(3, 10, 5, 90, 8, 0.5);

    assertEquals(new SessionCost(203, 1010, 505, 9090, 808, 0.5), thread.plusSince(reported, SessionCost.NONE));
  }

  @Test
  public void aResumeAddsOnlyWhatItCountsBeyondTheRestoredFigure() {
    SessionCost thread = new SessionCost(284, 1000, 500, 9000, 800, null);
    SessionCost restored = new SessionCost(281, 900, 400, 8000, 700, null);
    SessionCost reported = new SessionCost(285, 950, 450, 8500, 750, null);

    assertEquals(new SessionCost(288, 1050, 550, 9500, 850, null), thread.plusSince(reported, restored));
  }

  @Test
  public void aCountBelowTheRestoredFigureAddsNothing() {
    SessionCost thread = new SessionCost(50, 10, 10, 10, 10, null);
    SessionCost restored = new SessionCost(40, 10, 10, 10, 10, null);

    assertEquals(thread, thread.plusSince(new SessionCost(1, 1, 1, 1, 1, null), restored));
  }
}
