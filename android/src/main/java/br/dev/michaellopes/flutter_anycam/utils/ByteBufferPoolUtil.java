package br.dev.michaellopes.flutter_anycam.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public class ByteBufferPoolUtil {

    private static class Key {
        final int size;
        final ByteOrder order;

        Key(int size, ByteOrder order) {
            this.size = size;
            this.order = order;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return size == k.size && order == k.order;
        }

        @Override
        public int hashCode() {
            return 31 * size + order.hashCode();
        }
    }

    public static class PoolItem {
        public final ByteBuffer buffer;
        private final Key key;
        private final ByteBufferPoolUtil pool;

        private PoolItem(ByteBuffer buffer, Key key, ByteBufferPoolUtil pool) {
            this.buffer = buffer;
            this.key = key;
            this.pool = pool;
        }

        public void release() {
            pool.release(this);
        }
    }

    private final int maxPerKey;
    private final Map<Key, ArrayDeque<PoolItem>> pool = new HashMap<>();

    public ByteBufferPoolUtil(int maxPerKey) {
        this.maxPerKey = maxPerKey;
    }

    public ByteBufferPoolUtil() {
        this(4);
    }

    public synchronized PoolItem acquire(int size, ByteOrder order) {
        Key key = new Key(size, order);
        ArrayDeque<PoolItem> queue = pool.get(key);

        if (queue != null && !queue.isEmpty()) {
            PoolItem item = queue.poll();
            item.buffer.rewind();
            return item;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        buffer.order(order);
        return new PoolItem(buffer, key, this);
    }

    public PoolItem acquire(int size) {
        return acquire(size, ByteOrder.nativeOrder());
    }

    public synchronized void release(PoolItem item) {
        Key key = item.key;
        ArrayDeque<PoolItem> queue = pool.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (queue.size() < maxPerKey) {
            item.buffer.rewind();
            queue.offer(item);
        }
    }

    public synchronized void clear() {
        pool.clear();
    }
}