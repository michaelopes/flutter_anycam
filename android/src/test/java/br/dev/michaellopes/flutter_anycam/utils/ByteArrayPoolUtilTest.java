package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ByteArrayPoolUtilTest {

    private ByteArrayPoolUtil pool;

    @Before
    public void setUp() {
        pool = new ByteArrayPoolUtil(2, 30);
    }

    @After
    public void tearDown() {
        pool.shutdown();
    }

    @Test
    public void exhaustedPoolNeverBlocksForever() {
        ByteArrayPoolUtil.PoolItem a = pool.acquire(16);
        ByteArrayPoolUtil.PoolItem b = pool.acquire(16);
        long start = System.nanoTime();
        ByteArrayPoolUtil.PoolItem c = pool.acquire(16);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNotNull("an exhausted pool hands out an unpooled buffer", c);
        assertTrue(c.data.length >= 16);
        assertTrue("waited " + elapsedMs + "ms", elapsedMs < 2000);

        pool.release(c);
        assertEquals("unpooled buffers never join the pool", 2, pool.getTotalCount());
        pool.release(a);
        pool.release(b);
    }

    @Test
    public void timedAcquireReturnsNullWhenExhausted() {
        pool.acquire(8);
        pool.acquire(8);
        assertNull(pool.acquire(8, 50));
    }

    @Test
    public void releasedBufferIsReused() {
        ByteArrayPoolUtil.PoolItem a = pool.acquire(32);
        pool.release(a);
        assertSame(a, pool.acquire(16));
    }

    @Test
    public void fullPoolOfTooSmallIdleBuffersIsRecycled() {
        pool.release(pool.acquire(10));
        ByteArrayPoolUtil.PoolItem x = pool.acquire(10);
        ByteArrayPoolUtil.PoolItem y = pool.acquire(10);
        pool.release(x);
        pool.release(y);
        assertEquals(2, pool.getTotalCount());

        long start = System.nanoTime();
        ByteArrayPoolUtil.PoolItem big = pool.acquire(1000, 5000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull("idle small buffers must not make a bigger request wait forever", big);
        assertTrue(big.data.length >= 1000);
        assertTrue("waited " + elapsedMs + "ms", elapsedMs < 1000);
        assertTrue(pool.getTotalCount() <= 2);
    }

    @Test
    public void waiterIsWokenByRelease() throws Exception {
        ByteArrayPoolUtil.PoolItem a = pool.acquire(8);
        pool.acquire(8);
        AtomicReference<ByteArrayPoolUtil.PoolItem> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            got.set(pool.acquire(8, 5000));
            done.countDown();
        });
        waiter.start();
        Thread.sleep(50);
        pool.release(a);
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertSame(a, got.get());
    }

    @Test
    public void interruptedCallerGetsBufferAndKeepsFlag() {
        Thread.currentThread().interrupt();
        try {
            ByteArrayPoolUtil.PoolItem item = pool.acquire(8);
            assertNotNull(item);
            assertTrue("interrupt flag restored", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void releaseNullAndForeignItemsAreIgnored() {
        pool.release(null);
        ByteArrayPoolUtil other = new ByteArrayPoolUtil(1, 30);
        try {
            pool.release(other.acquire(8));
            assertEquals(0, pool.getTotalCount());
        } finally {
            other.shutdown();
        }
    }

    @Test
    public void clearKeepsPoolUsable() {
        pool.acquire(8);
        pool.clear();
        assertEquals(0, pool.getTotalCount());
        assertNotNull(pool.acquire(8));
        assertEquals(1, pool.getTotalCount());
    }

    @Test
    public void concurrentUseStaysWithinBounds() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < 8; t++) {
            final int size = 64 * (t + 1);
            exec.execute(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        ByteArrayPoolUtil.PoolItem item = pool.acquire(size);
                        if (item.data.length < size) throw new AssertionError("buffer too small");
                        item.data[size - 1] = 1;
                        pool.release(item);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS));
        exec.shutdownNow();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(pool.getTotalCount() <= 2);
    }
}
