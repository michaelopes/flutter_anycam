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

    /**
     * Obtém um byte[] de pelo menos {@code size} bytes.
     * Bloqueia indefinidamente até que um item seja liberado,
     * caso o pool esteja cheio e todos em uso.
     *
     * @param size tamanho mínimo do buffer
     * @return byte[] pronto para uso (pode ser maior que size)
     * @throws InterruptedException se a thread for interrompida enquanto aguarda
     */
    public PoolItem acquire(int size)  {

       try {
           lock.lockInterruptibly();
           try {
               while (true) {
                   // 1. Procura item disponível com tamanho suficiente
                   for (PoolItem item : items) {
                       if (item.state == State.AVAILABLE && item.data.length >= size) {
                           markInUse(item);
                           return item;
                       }
                   }

                   // 2. Pool não cheio → cria novo item
                   if (items.size() < maxItems) {
                       PoolItem item = new PoolItem(size);
                       markInUse(item);
                       items.add(item);
                       return item;
                   }

                   // 3. Pool cheio e nada disponível → aguarda sinal de release
                   available.await();
               }
           } finally {
               lock.unlock();
           }
       } catch ( InterruptedException e) {
         return null;
       }
    }

    /**
     * Variante com timeout: retorna null se não conseguir um buffer no prazo.
     *
     * @param size        tamanho mínimo do buffer
     * @param timeoutMs   tempo máximo de espera em milissegundos
     * @return byte[] ou null se timeout expirar
     * @throws InterruptedException se a thread for interrompida
     */
    public PoolItem acquire(int size, long timeoutMs) {
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            lock.lockInterruptibly();
            try {
                while (true) {
                    for (PoolItem item : items) {
                        if (item.state == State.AVAILABLE && item.data.length >= size) {
                            markInUse(item);
                            return item;
                        }
                    }

                    if (items.size() < maxItems) {
                        PoolItem item = new PoolItem(size);
                        markInUse(item);
                        items.add(item);
                        return item;
                    }

                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) return null;
                    available.await(remaining, TimeUnit.MILLISECONDS);
                }
            } finally {
                lock.unlock();
            }
        } catch ( InterruptedException e) {
            return null;
        }

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