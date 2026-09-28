package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

public class UsbFrameBufferPoolTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroCapacity() {
        new UsbFrameBufferPool(0);
    }

    @Test
    public void allocatesDirectNv21SizedBuffers() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        UsbFrameBufferPool.Frame frame = pool.acquire(640, 480);
        assertNotNull(frame);
        assertTrue(frame.buffer.isDirect());
        assertEquals(UsbFrameBufferPool.nv21Size(640, 480), frame.buffer.remaining());
    }

    @Test
    public void rejectsInvalidSize() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        assertNull(pool.acquire(0, 10));
        assertNull(pool.acquire(10, -1));
        assertEquals(0, pool.inFlight());
    }

    @Test
    public void backpressureAtMaxInFlight() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        UsbFrameBufferPool.Frame first = pool.acquire(4, 4);
        assertNotNull(first);
        assertNull("second acquire refused while first is unreleased", pool.acquire(4, 4));
        first.release();
        assertNotNull(pool.acquire(4, 4));
    }

    @Test
    public void copyOfDoesNotShareBackingWithSource() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        ByteBuffer src = ByteBuffer.allocateDirect(UsbFrameBufferPool.nv21Size(2, 2));
        for (int i = 0; i < src.capacity(); i++) {
            src.put((byte) (i + 1));
        }
        src.flip();

        UsbFrameBufferPool.Frame frame = pool.copyOf(src, 2, 2);
        assertNotNull(frame);
        assertNotSame(src, frame.buffer);
        assertEquals(1, frame.buffer.get(0));

        // Mutating source after copy must not corrupt the pooled frame.
        src.put(0, (byte) 99);
        assertEquals(1, frame.buffer.get(0));
        frame.release();
    }

    @Test
    public void copyOfReturnsNullUnderBackpressure() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        ByteBuffer src = ByteBuffer.allocateDirect(UsbFrameBufferPool.nv21Size(2, 2));
        src.clear();
        assertNotNull(pool.copyOf(src.duplicate(), 2, 2));
        src.clear();
        assertNull(pool.copyOf(src, 2, 2));
    }

    @Test
    public void copyOfRejectsTruncatedSourceWithoutConsumingASlot() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        ByteBuffer shortSrc = ByteBuffer.allocateDirect(UsbFrameBufferPool.nv21Size(4, 4) - 1);
        assertNull(pool.copyOf(shortSrc, 4, 4));
        assertEquals(0, pool.inFlight());
        assertEquals("source position untouched", 0, shortSrc.position());

        ByteBuffer fullSrc = ByteBuffer.allocateDirect(UsbFrameBufferPool.nv21Size(4, 4));
        assertNotNull("pool still usable after a rejected short frame", pool.copyOf(fullSrc, 4, 4));
    }

    @Test
    public void copyOfTruncatesOversizedSourceToOneFrame() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        int needed = UsbFrameBufferPool.nv21Size(2, 2);
        ByteBuffer src = ByteBuffer.allocateDirect(needed + 10);
        UsbFrameBufferPool.Frame frame = pool.copyOf(src, 2, 2);
        assertNotNull(frame);
        assertEquals(needed, frame.buffer.remaining());
        assertEquals("source position untouched", 0, src.position());
    }

    @Test
    public void copyOfRejectsInvalidSize() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        assertNull(pool.copyOf(ByteBuffer.allocateDirect(16), 0, 4));
        assertEquals(0, pool.inFlight());
    }

    @Test
    public void releaseRecyclesTheSameBuffer() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        UsbFrameBufferPool.Frame first = pool.acquire(4, 4);
        ByteBuffer buffer = first.buffer;
        first.release();
        UsbFrameBufferPool.Frame second = pool.acquire(4, 4);
        assertSame(buffer, second.buffer);
        second.release();
    }

    @Test
    public void doubleReleaseIsIgnored() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(2);
        UsbFrameBufferPool.Frame a = pool.acquire(4, 4);
        UsbFrameBufferPool.Frame b = pool.acquire(4, 4);
        a.release();
        a.release();
        assertEquals(1, pool.inFlight());
        b.release();
        assertEquals(0, pool.inFlight());
    }

    @Test
    public void sizeChangeDropsStaleBuffers() {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(1);
        UsbFrameBufferPool.Frame old = pool.acquire(4, 4);
        assertNull(pool.acquire(8, 8));
        old.release();
        assertEquals(0, pool.freeCount());
        UsbFrameBufferPool.Frame fresh = pool.acquire(8, 8);
        assertNotNull(fresh);
        assertEquals(UsbFrameBufferPool.nv21Size(8, 8), fresh.buffer.remaining());
        assertNotSame(old.buffer, fresh.buffer);
    }

    @Test
    public void concurrentReleaseKeepsCountsConsistent() throws Exception {
        UsbFrameBufferPool pool = new UsbFrameBufferPool(4);
        for (int round = 0; round < 500; round++) {
            UsbFrameBufferPool.Frame[] frames = new UsbFrameBufferPool.Frame[4];
            for (int i = 0; i < 4; i++) {
                frames[i] = pool.acquire(2, 2);
                assertNotNull(frames[i]);
            }
            Thread[] threads = new Thread[4];
            for (int i = 0; i < 4; i++) {
                UsbFrameBufferPool.Frame f = frames[i];
                threads[i] = new Thread(() -> {
                    f.release();
                    f.release();
                });
                threads[i].start();
            }
            for (Thread t : threads) t.join();
            assertEquals(0, pool.inFlight());
            assertTrue(pool.freeCount() <= 4);
        }
    }
}
