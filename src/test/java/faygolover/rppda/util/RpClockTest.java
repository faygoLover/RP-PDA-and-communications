package faygolover.rppda.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpClockTest {
    /** Сутки RP за 3 ч реального времени: 24000 тиков / 10 800 000 мс. */
    private static final double RATE = 24_000.0 / 10_800_000;

    /** Часы для теста: реальные мс и время суток мира (дробное — RP Time копит доли тика). */
    private static final class W {
        long ms = 1_000_000;
        double exact;

        long ticks() {
            return (long) exact;
        }
    }

    private static void run(RpClock c, W w, long seconds, double rate) {
        for (long i = 0; i < seconds; i++) {
            w.ms += 1000;
            w.exact += rate * 1000;
            c.sample(w.ms, w.ticks());
        }
    }

    private static void near(long want, long got) {
        assertTrue(Math.abs(want - got) <= RpClock.TOLERANCE, "ждали " + want + ", вышло " + got);
    }

    @Test
    void steadyRunNeedsNoPoints() {
        RpClock c = new RpClock();
        W s = new W();
        c.sample(s.ms, 0);
        run(c, s, 6 * 3600, RATE);
        assertEquals(2, c.points().length / 2, "ровный ход — одна точка и последнее показание");
        near(Math.round(RATE * 3600_000), c.ticksAt(1_000_000 + 3600_000));
    }

    @Test
    void pauseJumpAndOfflineGap() {
        RpClock c = new RpClock();
        W s = new W();
        c.sample(s.ms, 0);
        run(c, s, 3600, RATE);               // час идёт
        long beforePause = s.ms;
        long atPause = s.ticks();
        run(c, s, 1800, 0);                  // полчаса пауза
        long pauseEnd = s.ms;
        run(c, s, 1800, RATE);               // снова идёт
        long beforeJump = s.ms;
        s.exact += 6000;                        // /rptime add 6 ч
        run(c, s, 600, RATE);
        long afterJump = s.ms;
        long ticksAfterJump = s.ticks();

        near(atPause, c.ticksAt(beforePause + 900_000));       // середина паузы — время паузы
        near(atPause, c.ticksAt(pauseEnd));
        near(atPause + Math.round(RATE * 900_000), c.ticksAt(pauseEnd + 900_000));
        near(ticksAfterJump, c.ticksAt(afterJump));
        assertTrue(c.ticksAt(beforeJump - 2000) < c.ticksAt(beforeJump + 2000) - 5000, "прыжок виден");

        // Сохранить, «сервер выключен» сутки, снова запуск.
        RpClock back = new RpClock();
        back.load(c.points());
        long off = s.ms;
        s.ms += 86_400_000;
        back.sample(s.ms, s.ticks());
        run(back, s, 600, RATE);
        near(ticksAfterJump, back.ticksAt(off + 43_200_000));   // пока сервер стоял — время стоит
        near(s.ticks(), back.ticksAt(s.ms));
        near(atPause, back.ticksAt(beforePause + 900_000));     // старое — как было
    }

    @Test
    void calendar() {
        assertEquals("06:00", RpClock.clock(0));
        assertEquals("14:30", RpClock.clock(8500));
        assertEquals("00:00", RpClock.clock(18_000));
        assertEquals(1, RpClock.day(23_999));
        assertEquals(2, RpClock.day(24_000));
        assertEquals(0, RpClock.day(-1));
    }
}
