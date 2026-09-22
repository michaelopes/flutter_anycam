package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

public class ReadbackBufferPoolTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroCapacity() {
        new ReadbackBufferPool(0);
    }

    @Test
    public void allocatesDirectRgbaSizedBuffers() {
        ReadbackBufferPool pool = new ReadbackBufferPool(1);
        ReadbackBufferPool.Frame frame = pool.acquire(640, 360);
        assertNotNull(frame);
        assertTrue(frame.buffer.isDirect());
        assertEquals(640 * 360 * 4, frame.buffer.capacity());
        assertEquals(0, frame.buffer.position());
        assertEquals(frame.buffer.capacity(), frame.buffer.limit());
    }

    @Test
    public void rejectsInvalidSize() {
        ReadbackBufferPool pool = new ReadbackBufferPool(1);
        assertNull(pool.acquire(0, 10));
        assertNull(pool.acquire(10, -1));
        assertEquals(0, pool.inFlight());
    }

    @Test
    public void backpressureAtMaxInFlight() {
        ReadbackBufferPool pool = new ReadbackBufferPool(1);
        ReadbackBufferPool.Frame first = pool.acquire(4, 4);
        assertNotNull(first);
        assertNull("second readback must be refused while the first is unreleased", pool.acquire(4, 4));
        first.release();
        assertNotNull(pool.acquire(4, 4));
    }

    @Test
    public void releaseRecyclesTheSameBufferAndResetsIt() {
        ReadbackBufferPool pool = new ReadbackBufferPool(1);
        ReadbackBufferPool.Frame first = pool.acquire(4, 4);
        ByteBuffer buffer = first.buffer;
        buffer.position(10);
        first.release();

        ReadbackBufferPool.Frame second = pool.acquire(4, 4);
        assertSame(buffer, second.buffer);
        assertEquals(0, second.buffer.position());
    }

    @Test
    public void doubleReleaseIsIgnored() {
        ReadbackBufferPool pool = new ReadbackBufferPool(2);
        ReadbackBufferPool.Frame a = pool.acquire(4, 4);
        ReadbackBufferPool.Frame b = pool.acquire(4, 4);
        a.release();
        a.release();
        assertEquals(1, pool.inFlight());
        assertEquals(1, pool.freeCount());
        b.release();
        assertEquals(0, pool.inFlight());
        assertEquals(2, pool.freeCount());
    }

    @Test
    public void sizeChangeDropsStaleBuffersButStillCountsThemInFlight() {
        ReadbackBufferPool pool = new ReadbackBufferPool(1);
        ReadbackBufferPool.Frame old = pool.acquire(4, 4);

        assertNull("old-size frame still in flight → bounded memory", pool.acquire(8, 8));

        old.release();
        assertEquals(0, pool.freeCount());

        ReadbackBufferPool.Frame fresh = pool.acquire(8, 8);
        assertNotNull(fresh);
        assertEquals(8 * 8 * 4, fresh.buffer.capacity());
        assertNotSame(old.buffer, fresh.buffer);
    }

    @Test
    public void concurrentReleaseKeepsCountsConsistent() throws Exception {
        ReadbackBufferPool pool = new ReadbackBufferPool(4);
        for (int round = 0; round < 500; round++) {
            ReadbackBufferPool.Frame[] frames = new ReadbackBufferPool.Frame[4];
            for (int i = 0; i < 4; i++) {
                frames[i] = pool.acquire(2, 2);
                assertNotNull(frames[i]);
            }
            Thread[] threads = new Thread[4];
            for (int i = 0; i < 4; i++) {
                ReadbackBufferPool.Frame f = frames[i];
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
