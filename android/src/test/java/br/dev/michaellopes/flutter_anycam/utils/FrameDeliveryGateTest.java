package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class FrameDeliveryGateTest {

    @Test
    public void onlyOneFrameInFlight() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertTrue(gate.isIdle());
        FrameDeliveryGate.Delivery first = gate.tryBegin(1, null);
        assertNotNull(first);
        assertFalse(gate.isIdle());
        assertNull("second frame refused while the first is queued", gate.tryBegin(1, null));
        first.complete(true);
        assertTrue(gate.isIdle());
        assertNotNull(gate.tryBegin(1, null));
    }

    @Test
    public void fanOutFreesSlotOnlyAfterEverySend() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        FrameDeliveryGate.Delivery d = gate.tryBegin(3, null);
        d.complete(true);
        d.complete(true);
        assertFalse("one bridge still has the frame queued", gate.isIdle());
        d.complete(true);
        assertTrue(gate.isIdle());
    }

    @Test
    public void cleanupRunsOnceWhenNoSendWasDelivered() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        AtomicInteger closed = new AtomicInteger();
        FrameDeliveryGate.Delivery d = gate.tryBegin(2, closed::incrementAndGet);
        d.complete(false);
        d.complete(false);
        assertEquals(1, closed.get());
        assertTrue(gate.isIdle());
    }

    @Test
    public void cleanupSkippedWhenAnySendWasDelivered() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        AtomicInteger closed = new AtomicInteger();
        FrameDeliveryGate.Delivery d = gate.tryBegin(2, closed::incrementAndGet);
        d.complete(false);
        d.complete(true);
        assertEquals("Dart owns the frame once any listener got it", 0, closed.get());
    }

    @Test
    public void zeroSendsRunsCleanupImmediatelyWithoutClaiming() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        AtomicInteger closed = new AtomicInteger();
        assertNull(gate.tryBegin(0, closed::incrementAndGet));
        assertEquals(1, closed.get());
        assertTrue(gate.isIdle());
    }

    @Test
    public void refusedBeginDoesNotRunCleanup() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        gate.tryBegin(1, null);
        AtomicInteger closed = new AtomicInteger();
        assertNull(gate.tryBegin(1, closed::incrementAndGet));
        assertEquals("caller owns cleanup of a refused frame", 0, closed.get());
    }

    @Test
    public void extraCompletionsAreIgnored() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        AtomicInteger closed = new AtomicInteger();
        FrameDeliveryGate.Delivery d = gate.tryBegin(1, closed::incrementAndGet);
        d.complete(false);
        FrameDeliveryGate.Delivery next = gate.tryBegin(1, null);
        assertNotNull(next);
        d.complete(false);
        assertFalse("stale completion must not free the next frame's slot", gate.isIdle());
        assertEquals(1, closed.get());
    }

    @Test
    public void concurrentProducersNeverHoldTwoSlots() throws Exception {
        for (int round = 0; round < 300; round++) {
            FrameDeliveryGate gate = new FrameDeliveryGate();
            AtomicInteger claimed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread[] threads = new Thread[6];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    if (gate.tryBegin(1, null) != null) claimed.incrementAndGet();
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread t : threads) t.join();
            assertEquals(1, claimed.get());
        }
    }

    @Test
    public void concurrentCompletionsFreeExactlyOnce() throws Exception {
        for (int round = 0; round < 300; round++) {
            FrameDeliveryGate gate = new FrameDeliveryGate();
            AtomicInteger closed = new AtomicInteger();
            FrameDeliveryGate.Delivery d = gate.tryBegin(4, closed::incrementAndGet);
            CountDownLatch start = new CountDownLatch(1);
            Thread[] threads = new Thread[4];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    d.complete(false);
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread t : threads) t.join();
            assertTrue(gate.isIdle());
            assertEquals(1, closed.get());
        }
    }
}
