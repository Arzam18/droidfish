package org.petero.droidfish.tournament;

import org.junit.Test;
import static org.junit.Assert.*;

public class CetClockTest {
    @Test public void uciCommandIncludesBothClocksAndIncrements() {
        CetClock clock = new CetClock(30000, 500);
        assertEquals("go wtime 30000 btime 30000 winc 500 binc 500", clock.uciGo());
    }
    @Test public void acceptsQuickMoveAndAddsIncrement() throws Exception {
        CetClock clock = new CetClock(10000, 100);
        clock.startTurn(true);
        Thread.sleep(5);
        assertTrue(clock.acceptMove(true));
        assertTrue(clock.remainingMs(true) >= 10000);
    }
    @Test public void zeroClockFlagsImmediately() {
        CetClock clock = new CetClock(0, 0);
        clock.startTurn(true);
        assertTrue(clock.isExpired());
        assertFalse(clock.acceptMove(true));
    }
    @Test public void adjudicatesUsingBestmoveArrivalTimestamp() {
        CetClock clock = new CetClock(1, 0);
        clock.startTurn(true);
        assertFalse(clock.acceptMove(true, System.nanoTime() + 2_000_000L));
    }
    @Test public void rejectsWrongSideMove() {
        CetClock clock = new CetClock(1000, 0);
        clock.startTurn(true);
        assertFalse(clock.acceptMove(false));
    }
}
