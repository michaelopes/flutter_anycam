package br.dev.michaellopes.flutter_anycam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class CameraEventGateTest {

    @Test
    public void connectedIsReportedOnceAcrossResolutionChanges() {
        CameraEventGate gate = new CameraEventGate();
        assertTrue(gate.onConnected());
        assertFalse(gate.onConnected());
        assertFalse(gate.onConnected());
    }

    @Test
    public void failureIsNotOverwrittenByTheFollowingDisconnect() {
        CameraEventGate gate = new CameraEventGate();
        assertTrue(gate.onConnected());
        assertTrue("onFailed", gate.onTerminal());
        assertFalse("library disconnected after failure", gate.onTerminal());
    }

    @Test
    public void unauthorizedBeforeConnectBlocksLateConnect() {
        CameraEventGate gate = new CameraEventGate();
        assertTrue(gate.onTerminal());
        assertFalse("late open callback after cancel", gate.onConnected());
    }

    @Test
    public void nothingIsEmittedAfterDispose() {
        CameraEventGate gate = new CameraEventGate();
        gate.dispose();
        assertTrue(gate.isDisposed());
        assertFalse(gate.onConnected());
        assertFalse(gate.onTerminal());
    }

    @Test
    public void disposeAfterConnectSuppressesOwnTeardownDisconnect() {
        CameraEventGate gate = new CameraEventGate();
        assertTrue(gate.onConnected());
        assertTrue(gate.isConnected());
        gate.dispose();
        assertFalse(gate.isConnected());
        assertFalse("our own stop must not surface as disconnected", gate.onTerminal());
    }

    @Test
    public void newSessionAllowsConnectedAgainAfterDisconnect() {
        CameraEventGate gate = new CameraEventGate();
        int first = gate.beginSession();
        assertTrue(gate.onConnected(first));
        assertTrue("unplug", gate.onTerminal(first));
        assertFalse(gate.onConnected(first));

        int second = gate.beginSession();
        assertTrue("replug reconnects the same view", gate.onConnected(second));
        assertTrue(gate.isConnected());
        assertTrue(gate.onTerminal(second));
    }

    @Test
    public void lateEventsOfPreviousSessionAreDropped() {
        CameraEventGate gate = new CameraEventGate();
        int first = gate.beginSession();
        assertTrue(gate.onConnected(first));
        int second = gate.beginSession();
        assertFalse("old teardown must not hide the new device", gate.onTerminal(first));
        assertFalse(gate.onConnected(first));
        assertTrue(gate.isCurrentSession(second));
        assertFalse(gate.isCurrentSession(first));
        assertTrue(gate.onConnected(second));
    }

    @Test
    public void sessionRulesStillApplyWithinOneSession() {
        CameraEventGate gate = new CameraEventGate();
        int s = gate.beginSession();
        assertTrue("cancel", gate.onTerminal(s));
        assertFalse("late open after cancel", gate.onConnected(s));
        assertFalse(gate.onTerminal(s));
    }

    @Test
    public void disposeEndsEverySession() {
        CameraEventGate gate = new CameraEventGate();
        int s = gate.beginSession();
        gate.dispose();
        assertFalse(gate.isCurrentSession(s));
        assertFalse(gate.onConnected(s));
        assertFalse(gate.onTerminal(s));
        int next = gate.beginSession();
        assertFalse(gate.onConnected(next));
    }

    @Test
    public void racingThreadsProduceExactlyOneTerminalEvent() throws Exception {
        for (int round = 0; round < 200; round++) {
            CameraEventGate gate = new CameraEventGate();
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
