package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FrameRateLimiterUtilTest {

    private static final class Limiter extends FrameRateLimiterUtil<Integer> {
        final List<Integer> limited = new ArrayList<>();
        final List<Integer> skipped = new ArrayList<>();

        Limiter(int fps, AtomicLong clock) {
            super(fps, clock::get);
        }

        @Override
        protected void onFrameLimited(Integer data) {
            limited.add(data);
        }

        @Override
        protected void onFrameSkipped(Integer data) {
            skipped.add(data);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveFps() {
        new Limiter(0, new AtomicLong());
    }

    @Test
    public void firstFrameAlwaysPassesEvenAtClockZero() {
        AtomicLong clock = new AtomicLong(0);
        Limiter limiter = new Limiter(5, clock);
        assertTrue(limiter.isFrameDue());
        assertTrue(limiter.shouldProcessFrame());
        assertFalse(limiter.shouldProcessFrame());
    }

    @Test
    public void steadyStreamIsCappedAtTargetFps() {
        AtomicLong clock = new AtomicLong(10_000);
        Limiter limiter = new Limiter(5, clock);
        int passed = 0;
        // 30 fps source for 10 s → expect ~5 fps (50 frames, +1 for the first frame).
        for (int i = 0; i < 300; i++) {
            clock.set(10_000 + Math.round(i * (1000.0 / 30)));
            if (limiter.shouldProcessFrame()) passed++;
        }
        assertTrue("passed=" + passed, passed >= 49 && passed <= 51);
    }

    @Test
    public void isFrameDueDoesNotConsumeTheSlot() {
        AtomicLong clock = new AtomicLong(1_000);
        Limiter limiter = new Limiter(10, clock);
        assertTrue(limiter.shouldProcessFrame());

        clock.set(1_050);
        assertFalse(limiter.isFrameDue());
        assertFalse(limiter.shouldProcessFrame());

        clock.set(1_100);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.isFrameDue());
        }
        assertTrue(limiter.shouldProcessFrame());
        assertFalse(limiter.isFrameDue());
    }

    @Test
    public void jitterDoesNotDriftTheAverageRate() {
        AtomicLong clock = new AtomicLong(0);
        Limiter limiter = new Limiter(10, clock);
        assertTrue(limiter.shouldProcessFrame());
        // Frames arrive 5 ms late every slot; the schedule must stay anchored at 100 ms steps.
        for (int slot = 1; slot <= 10; slot++) {
            clock.set(slot * 100L + 5);
            assertTrue("slot " + slot, limiter.shouldProcessFrame());
            clock.set(slot * 100L + 60);
            assertFalse(limiter.shouldProcessFrame());
        }
    }

    @Test
    public void stallDoesNotReleaseABurst() {
        AtomicLong clock = new AtomicLong(1_000);
        Limiter limiter = new Limiter(5, clock);
        assertTrue(limiter.shouldProcessFrame());

        // 10 s network stall, then frames resume at 30 fps.
        clock.set(11_000);
        assertTrue(limiter.shouldProcessFrame());
        int burst = 0;
        for (int i = 1; i <= 5; i++) {
            clock.set(11_000 + i * 33L);
            if (limiter.shouldProcessFrame()) burst++;
        }
        assertEquals("no catch-up frames within the next 200 ms", 0, burst);

        clock.set(11_200);
        assertTrue(limiter.shouldProcessFrame());
    }

    @Test
    public void onNewFrameRoutesToLimitedOrSkipped() {
        AtomicLong clock = new AtomicLong(0);
        Limiter limiter = new Limiter(10, clock);
        limiter.onNewFrame(1);
        clock.set(50);
        limiter.onNewFrame(2);
        clock.set(100);
        limiter.onNewFrame(3);
        assertEquals(List.of(1, 3), limiter.limited);
        assertEquals(List.of(2), limiter.skipped);
    }

    @Test
    public void concurrentCallersNeverDoubleConsumeASlot() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        Limiter limiter = new Limiter(10, clock);
        AtomicInteger passed = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10_000; i++) {
                        if (limiter.shouldProcessFrame()) passed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals("clock frozen → only the first frame passes", 1, passed.get());
    }
}
