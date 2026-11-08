package br.dev.michaellopes.flutter_anycam.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pool de byte[] com:
 *  - limite máximo de itens (maxItems)
 *  - bloqueio com espera quando todos os itens estão em uso
 *  - auto-deleção de itens ociosos após itemTimeoutSeconds
 *
 * Uso típico:
 *   ByteArrayPool pool = new ByteArrayPool(8, 30); // até 8 itens, timeout 30s
 *   byte[] buf = pool.acquire(4096);
 *   try {
 *       // usa buf...
 *   } finally {
 *       pool.release(buf);
 *   }
 *   pool.shutdown(); // chame ao destruir o pool
 */
public class ByteArrayPoolUtil {

    // --- Estado de cada slot no pool ---
    private enum State { AVAILABLE, IN_USE }

    public static final class PoolItem {
        public final byte[] data;
        volatile State state;
        volatile long lastUsedMs;

        PoolItem(int size) {
            this.data       = new byte[size];
            this.state      = State.AVAILABLE;
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    // --- Configuração ---
    private final int  maxItems;
    private final long itemTimeoutMs;

    // --- Estado interno ---
    private final List<PoolItem>          items = new ArrayList<>();
    private final ReentrantLock           lock  = new ReentrantLock();
    private final Condition               available = lock.newCondition();
    private final ScheduledExecutorService cleaner;

    /**
     * @param maxItems           número máximo de byte[] mantidos no pool
     * @param itemTimeoutSeconds segundos de ociosidade antes de um item ser removido
     */
    public ByteArrayPoolUtil(int maxItems, long itemTimeoutSeconds) {
        if (maxItems <= 0)          throw new IllegalArgumentException("maxItems must be > 0");
        if (itemTimeoutSeconds <= 0) throw new IllegalArgumentException("itemTimeoutSeconds must be > 0");

        this.maxItems      = maxItems;
        this.itemTimeoutMs = itemTimeoutSeconds * 1000L;

        // Limpeza periódica — a cada metade do timeout
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ByteArrayPool-Cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(
                this::evictExpiredItems,
                itemTimeoutSeconds / 2,
                itemTimeoutSeconds / 2,
                TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /** Max wait for a pooled buffer before handing out an unpooled one. */
    private static final long ACQUIRE_WAIT_MS = 250;

    /**
     * Obtém um byte[] de pelo menos {@code size} bytes. Never blocks for long and never
     * returns null: when every pooled buffer stays in use past {@link #ACQUIRE_WAIT_MS}, an
     * unpooled buffer is returned ({@link #release} ignores it).
     */
    public PoolItem acquire(int size) {
        PoolItem item = acquire(size, ACQUIRE_WAIT_MS);
        return item != null ? item : newUnpooled(size);
    }

    /**
     * Variante com timeout: retorna null se não conseguir um buffer no prazo.
     */
    public PoolItem acquire(int size, long timeoutMs) {
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            lock.lockInterruptibly();
            try {
                while (true) {
                    PoolItem item = tryAcquireLocked(size);
                    if (item != null) return item;

                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) return null;
                    available.await(remaining, TimeUnit.MILLISECONDS);
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private PoolItem tryAcquireLocked(int size) {
        for (PoolItem item : items) {
            if (item.state == State.AVAILABLE && item.data.length >= size) {
                markInUse(item);
                return item;
            }
        }
        if (items.size() >= maxItems) {
            // A full pool of idle-but-too-small buffers would otherwise wait forever:
            // no release is coming. Replace one of them.
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).state == State.AVAILABLE) {
                    items.remove(i);
                    break;
                }
            }
        }
        if (items.size() < maxItems) {
            PoolItem item = new PoolItem(size);
            markInUse(item);
            items.add(item);
            return item;
        }
        return null;
    }

    private static PoolItem newUnpooled(int size) {
        PoolItem item = new PoolItem(size);
        item.state = State.IN_USE;
        return item;
    }

    public void release(PoolItem item) {
        if (item == null) return;
        lock.lock();
        try {
            for (PoolItem it : items) {
                if (it == item) {         // comparação por referência
                    it.state      = State.AVAILABLE;
                    it.lastUsedMs = System.currentTimeMillis();
                    available.signalAll();        // acorda threads esperando
                    return;
                }
            }
            // buffer não pertence a este pool → ignora silenciosamente
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra o pool e o cleaner. Chame quando o pool não for mais necessário.
     */
    public void shutdown() {
        cleaner.shutdown();
        clear();
    }

    /** Drops every pooled buffer; the pool stays usable. In-use buffers are simply forgotten. */
    public void clear() {
        lock.lock();
        try {
            items.clear();
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Diagnóstico
    // -------------------------------------------------------------------------

    /** @return número total de itens no pool (em uso + disponíveis) */
    public int getTotalCount() {
        lock.lock();
        try { return items.size(); }
        finally { lock.unlock(); }
    }

    /** @return número de itens disponíveis no momento */
    public int getAvailableCount() {
        lock.lock();
        try {
            int n = 0;
            for (PoolItem item : items)
                if (item.state == State.AVAILABLE) n++;
            return n;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void markInUse(PoolItem item) {
        item.state      = State.IN_USE;
        item.lastUsedMs = System.currentTimeMillis();
    }

    /**
     * Remove itens AVAILABLE que ficaram ociosos por mais de itemTimeoutMs.
     * Executado pelo ScheduledExecutor — não pelo caller.
     */
    private void evictExpiredItems() {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            items.removeIf(item ->
                    item.state == State.AVAILABLE &&
                            (now - item.lastUsedMs) >= itemTimeoutMs
            );
        } finally {
            lock.unlock();
        }
    }
}