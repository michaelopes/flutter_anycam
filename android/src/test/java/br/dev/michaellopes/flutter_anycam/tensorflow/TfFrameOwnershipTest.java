package br.dev.michaellopes.flutter_anycam.tensorflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Frame ownership rules shared with Dart by id. Buffers are null here: the rules are pure
 * bookkeeping and must hold regardless of the pixel storage.
 */
public class TfFrameOwnershipTest {

    private TfFrameHandler handler;

    @Before
    public void setUp() {
        handler = TfFrameHandler.getInstance();
        handler.closeAll();
        assertEquals(0, handler.getFramesSize());
    }

    @After
    public void tearDown() {
        handler.closeAll();
    }

    private TfFrameHandler.TfFrame frame(String parentId, boolean source) {
        return handler.register(handler.new TfFrame(4, 4, parentId, null, source));
    }

    @Test
    public void ownerCloseReleasesFrame() {
        TfFrameHandler.TfFrame f = frame(null, true);
        assertNotNull(handler.getFrameById(f.id));
        f.close();
        assertTrue(f.isReleased());
        assertNull(handler.getFrameById(f.id));
    }

    @Test
    public void closeIsIdempotent() {
        TfFrameHandler.TfFrame parent = frame(null, true);
        TfFrameHandler.TfFrame child = frame(parent.id, false);
        child.close();
        child.close();
        handler.closeFrame(child.id);
        // A repeated child close must not decrement the parent's child count twice.
        assertFalse(parent.isReleased());
        parent.close();
        assertTrue(parent.isReleased());
        assertEquals(0, handler.getFramesSize());
    }

    @Test
    public void childKeepsClosedParentAlive() {
        TfFrameHandler.TfFrame parent = frame(null, true);
        TfFrameHandler.TfFrame child = frame(parent.id, false);
        parent.close();
        assertFalse("parent buffer still read through the child", parent.isReleased());
        assertNotNull(handler.getFrameById(parent.id));
        child.close();
        assertTrue(child.isReleased());
        assertTrue(parent.isReleased());
    }

    @Test
    public void dartOwnedParentSurvivesChildClose() {
        TfFrameHandler.TfFrame parent = frame(null, true);
        TfFrameHandler.TfFrame child = frame(parent.id, false);
        child.close();
        assertFalse("closing a derived frame must never close the frame Dart still holds", parent.isReleased());
        parent.close();
        assertTrue(parent.isReleased());
    }

    @Test
    public void internalParentIsReleasedWithItsLastChild() {
        TfFrameHandler.TfFrame letterbox = frame(null, false);
        letterbox.closeWhenChildClosed = true;
        TfFrameHandler.TfFrame a = frame(letterbox.id, false);
        TfFrameHandler.TfFrame b = frame(letterbox.id, false);
        a.close();
        assertFalse(letterbox.isReleased());
        b.close();
        assertTrue(letterbox.isReleased());
    }

    @Test
    public void inferenceChainReleasesInOrder() {
        TfFrameHandler.TfFrame input = frame(null, true);
        TfFrameHandler.TfFrame letterbox = frame(input.id, false);
        letterbox.closeWhenChildClosed = true;
        TfFrameHandler.TfFrame modelInput = frame(letterbox.id, false);

        // Two inference results pin the model input; the inference call then drops ownership.
        assertTrue(modelInput.pin());
        assertTrue(modelInput.pin());
        modelInput.close();
        assertFalse(modelInput.isReleased());

        modelInput.unpin();
        assertFalse("one result still alive", modelInput.isReleased());
        modelInput.unpin();
        assertTrue(modelInput.isReleased());
        assertTrue(letterbox.isReleased());
        assertFalse("Dart still owns the camera frame", input.isReleased());

        input.close();
        assertTrue(input.isReleased());
        assertEquals(0, handler.getFramesSize());
    }

    @Test
    public void pinKeepsFrameAliveAcrossOwnerClose() {
        TfFrameHandler.TfFrame f = frame(null, true);
        assertTrue(f.pin());
        f.close();
        assertFalse(f.isReleased());
        f.unpin();
        assertTrue(f.isReleased());
        assertFalse("a released frame can never be pinned again", f.pin());
    }

    @Test
    public void extraUnpinIsHarmless() {
        TfFrameHandler.TfFrame f = frame(null, true);
        f.unpin();
        assertTrue(f.pin());
        f.close();
        assertFalse(f.isReleased());
        f.unpin();
        assertTrue(f.isReleased());
    }

    @Test
    public void closeAllWaitsForPinnedReaders() {
        TfFrameHandler.TfFrame pinned = frame(null, true);
        TfFrameHandler.TfFrame child = frame(pinned.id, false);
        TfFrameHandler.TfFrame free = frame(null, true);
        assertTrue(pinned.pin());

        handler.closeAll();
        assertTrue(free.isReleased());
        assertTrue(child.isReleased());
        assertFalse(pinned.isReleased());

        pinned.unpin();
        assertTrue(pinned.isReleased());
        assertEquals(0, handler.getFramesSize());
    }

    @Test
    public void sourceFramesAreBounded() {
        List<TfFrameHandler.TfFrame> open = new ArrayList<>();
        for (int i = 0; i < TfFrameHandler.MAX_SOURCE_FRAMES; i++) {
            assertTrue(handler.hasSourceCapacity());
            open.add(frame(null, true));
        }
        assertFalse("a consumer that never closes frames must hit the cap", handler.hasSourceCapacity());

        // Derived frames do not count against the camera budget.
        TfFrameHandler.TfFrame derived = frame(open.get(0).id, false);
        assertFalse(handler.hasSourceCapacity());

        open.get(1).close();
        assertTrue(handler.hasSourceCapacity());

        derived.close();
        for (TfFrameHandler.TfFrame f : open) f.close();
        assertEquals(0, handler.getFramesSize());
    }

    @Test
    public void concurrentCloseAndPinNeverDoubleRelease() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 200; round++) {
                TfFrameHandler.TfFrame parent = frame(null, true);
                TfFrameHandler.TfFrame child = frame(parent.id, false);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(4);
                Runnable reader = () -> {
                    try {
                        start.await();
                        if (child.pin()) child.unpin();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                };
                pool.execute(reader);
                pool.execute(reader);
                pool.execute(() -> {
                    try {
                        start.await();
                        child.close();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
                pool.execute(() -> {
                    try {
                        start.await();
                        parent.close();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
                start.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertTrue(child.isReleased());
                assertTrue(parent.isReleased());
            }
            assertEquals(0, handler.getFramesSize());
            assertTrue(handler.hasSourceCapacity());
        } finally {
            pool.shutdownNow();
        }
    }
}
