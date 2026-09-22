package br.dev.michaellopes.flutter_anycam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class RtspEventGateTest {

    @Test
    public void connectedIsReportedOnceAcrossResolutionChanges() {
        RtspEventGate gate = new RtspEventGate();
        assertTrue(gate.onConnected());
        assertFalse(gate.onConnected());
        assertFalse(gate.onConnected());
    }

    @Test
    public void failureIsNotOverwrittenByTheFollowingDisconnect() {
        RtspEventGate gate = new RtspEventGate();
        assertTrue(gate.onConnected());
        assertTrue("onRtspStatusFailed", gate.onTerminal());
        assertFalse("library disconnected after failure", gate.onTerminal());
    }

    @Test
    public void unauthorizedBeforeConnectBlocksLateConnect() {
        RtspEventGate gate = new RtspEventGate();
        assertTrue(gate.onTerminal());
        assertFalse("late frame-size callback from a dying decoder", gate.onConnected());
    }

    @Test
    public void nothingIsEmittedAfterDispose() {
        RtspEventGate gate = new RtspEventGate();
        gate.dispose();
        assertTrue(gate.isDisposed());
        assertFalse(gate.onConnected());
        assertFalse(gate.onTerminal());
    }

    @Test
    public void disposeAfterConnectSuppressesTheOwnStopDisconnect() {
        RtspEventGate gate = new RtspEventGate();
        assertTrue(gate.onConnected());
        gate.dispose();
        assertFalse("our own processor.stop() must not surface as disconnected", gate.onTerminal());
    }

    @Test
    public void racingThreadsProduceExactlyOneTerminalEvent() throws Exception {
        for (int round = 0; round < 200; round++) {
            RtspEventGate gate = new RtspEventGate();
            AtomicInteger terminals = new AtomicInteger();
            AtomicInteger connects = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread[] threads = new Thread[6];
            for (int i = 0; i < threads.length; i++) {
                final boolean terminal = i % 2 == 0;
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    if (terminal) {
                        if (gate.onTerminal()) terminals.incrementAndGet();
                    } else {
                        if (gate.onConnected()) connects.incrementAndGet();
                    }
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread t : threads) t.join();
            assertEquals(1, terminals.get());
            assertTrue(connects.get() <= 1);
        }
    }
}
