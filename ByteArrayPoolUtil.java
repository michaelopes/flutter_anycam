package br.dev.michaellopes.flutter_anycam.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pool genérico de byte[].
 * <p>
 * Acquire por tamanho : pool.acquire(1024)
 * Acquire por chave   : pool.acquire("frame_buffer")
 * Acquire chave+size  : pool.acquire("frame_buffer", 1024)
 * Release             : pool.release(entry)
 * <p>
 * Entradas ociosas (idle) que não forem reutilizadas dentro de
 * Config#idleTimeoutMs são removidas automaticamente pelo thread de eviction,
 * liberando a memória para o GC.
 * <p>
 * Thread-safe via ReentrantLock.
 */
public class ByteArrayPoolUtil {

    // -------------------------------------------------------------------------
    // Entry — handle público retornado ao chamador
    // -------------------------------------------------------------------------

    public static final class Entry {
        public final byte[] data;
        final String key;          // null → adquirido por tamanho
        volatile boolean inUse;
        long idleSince;             // epoch-ms do último release; 0 = em uso

        Entry(byte[] data, String key) {
            this.data = data;
            this.key = key;
            this.inUse = true;
        }

        public int size() {
            return data.length;
        }

        @Override
        public String toString() {
            return "Entry{key=" + key + ", size=" + data.length + ", inUse=" + inUse + "}";
        }
    }

    // -------------------------------------------------------------------------
    // Callback de eviction
    // -------------------------------------------------------------------------

    public interface EvictionListener {
        /**
         * Chamado fora do lock quando uma entrada expira e é removida.
         */
        void onEvicted(Entry entry);
    }

    // -------------------------------------------------------------------------
    // Configuração
    // -------------------------------------------------------------------------

    public static final class Config {

        /**
         * Máximo de entradas ociosas mantidas no pool ao mesmo tempo.
         */
        int maxIdleEntries = 32;

        /**
         * Aceita um buffer ocioso até este fator maior que o tamanho pedido.
         * Ex.: 2.0 → pediu 1 KB, aceita até 2 KB do pool.
         */
        float sizeToleranceFactor = 2.0f;

        /**
         * Zera o array ao fazer release (mais seguro, levemente mais lento).
         */
        boolean clearOnRelease = false;

        /**
         * Tempo máximo (ms) que uma entrada pode ficar ociosa no pool.
         * Após esse tempo ela é removida e o GC pode coletar o byte[].
         * 0 = desabilitado (entradas vivem até clear() ou shutdown()).
         */
        long idleTimeoutMs = 0;

        /**
         * Intervalo (ms) entre varreduras de eviction.
         * Se não configurado, usa idleTimeoutMs / 2 (mínimo 1 s).
         */
        long evictionIntervalMs = 0;

        /**
         * Listener opcional notificado a cada entry evictada.
         */
        EvictionListener evictionListener = null;

        public Config() {
        }

        public Config maxIdle(int n) {
            maxIdleEntries = n;
            return this;
        }

        public Config tolerance(float f) {
            sizeToleranceFactor = f;
            return this;
        }

        public Config clearOnRelease(boolean b) {
            clearOnRelease = b;
            return this;
        }

        public Config idleTimeout(long ms) {
            idleTimeoutMs = ms;
            return this;
        }

        public Config idleTimeout(long t, TimeUnit u) {
            idleTimeoutMs = u.toMillis(t);
            return this;
        }

        public Config evictionInterval(long ms) {
            evictionIntervalMs = ms;
            return this;
        }

        public Config evictionInterval(long t, TimeUnit u) {
            evictionIntervalMs = u.toMillis(t);
            return this;
        }

        public Config onEviction(EvictionListener l) {
            evictionListener = l;
            return this;
        }
    }

    // -------------------------------------------------------------------------
    // Campos internos
    // -------------------------------------------------------------------------

    private final Config config;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<String, List<Entry>> idleByKey = new HashMap<>();
    private final List<Entry> idleBySize = new ArrayList<>();
    private final List<Entry> inUse = new ArrayList<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> evictionTask;

    // -------------------------------------------------------------------------
    // Construção
    // -------------------------------------------------------------------------

    public ByteArrayPoolUtil() {
        this(new Config());
    }

    public ByteArrayPoolUtil(Config config) {
        this.config = config;
        if (config.idleTimeoutMs > 0) startEviction();
    }

    // -------------------------------------------------------------------------
    // Acquire por tamanho
    // -------------------------------------------------------------------------

    public Entry acquire(int minSize) {
        if (minSize <= 0) throw new IllegalArgumentException("minSize deve ser > 0");

        lock.lock();
        try {
            int maxAcceptable = (int) (minSize * config.sizeToleranceFactor);
            Entry best = null;
            int bestSz = Integer.MAX_VALUE;
            int bestIdx = -1;

            for (int i = 0; i < idleBySize.size(); i++) {
                Entry e = idleBySize.get(i);
                int sz = e.data.length;
                if (sz >= minSize && sz <= maxAcceptable && sz < bestSz) {
                    best = e;
                    bestSz = sz;
                    bestIdx = i;
                }
            }

            if (best != null) {
                idleBySize.remove(bestIdx);
                markInUse(best);
                return best;
            }

            Entry e = new Entry(new byte[minSize], null);
            inUse.add(e);
            return e;

        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Acquire por chave
    // -------------------------------------------------------------------------

    public Entry acquire(String key) {
        return acquire(key, 0);
    }

    public Entry acquire(String key, int minSize) {
        if (key == null) throw new IllegalArgumentException("key não pode ser null");

        lock.lock();
        try {
            List<Entry> bucket = idleByKey.get(key);
            if (bucket != null) {
                Entry best = null;
                int bestIdx = -1;
                for (int i = 0; i < bucket.size(); i++) {
                    Entry e = bucket.get(i);
                    if (e.data.length >= minSize && (best == null || e.data.length < best.data.length)) {
                        best = e;
                        bestIdx = i;
                    }
                }
                if (best != null) {
                    bucket.remove(bestIdx);
                    if (bucket.isEmpty()) idleByKey.remove(key);
                    markInUse(best);
                    return best;
                }
            }

            int sz = Math.max(minSize, 0);
            Entry e = new Entry(sz > 0 ? new byte[sz] : new byte[0], key);
            inUse.add(e);
            return e;

        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Release
    // -------------------------------------------------------------------------

    public void release(Entry entry) {
        if (entry == null) return;
        lock.lock();
        try {
            if (!entry.inUse) return;

            inUse.remove(entry);
            entry.inUse = false;
            entry.idleSince = System.currentTimeMillis(); // inicia contagem idle

            if (config.clearOnRelease) java.util.Arrays.fill(entry.data, (byte) 0);

            if (totalIdle() >= config.maxIdleEntries) return; // pool cheio — GC coleta

            if (entry.key != null) {
                idleByKey.computeIfAbsent(entry.key, k -> new ArrayList<>()).add(entry);
            } else {
                idleBySize.add(entry);
            }
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Engine de eviction
    // -------------------------------------------------------------------------

    private void startEviction() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ByteArrayPool-eviction");
            t.setDaemon(true);
            return t;
        });

        long interval = config.evictionIntervalMs > 0
                ? config.evictionIntervalMs
                : Math.max(1_000L, config.idleTimeoutMs / 2);

        evictionTask = scheduler.scheduleWithFixedDelay(
                this::evictExpiredIdle, interval, interval, TimeUnit.MILLISECONDS
        );
    }

    /**
     * Varre os buckets idle e remove entradas ociosas há mais de idleTimeoutMs.
     * Chamado pelo scheduler; pode ser invocado manualmente se necessário.
     */
    public void evictExpiredIdle() {
        long now = System.currentTimeMillis();
        long timeout = config.idleTimeoutMs;
        List<Entry> evicted = new ArrayList<>();

        lock.lock();
        try {
            // bucket por tamanho
            Iterator<Entry> it = idleBySize.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (now - e.idleSince >= timeout) {
                    it.remove();
                    evicted.add(e);
                }
            }

            // buckets por chave
            Iterator<Map.Entry<String, List<Entry>>> mapIt = idleByKey.entrySet().iterator();
            while (mapIt.hasNext()) {
                Map.Entry<String, List<Entry>> bucket = mapIt.next();
                Iterator<Entry> bIt = bucket.getValue().iterator();
                while (bIt.hasNext()) {
                    Entry e = bIt.next();
                    if (now - e.idleSince >= timeout) {
                        bIt.remove();
                        evicted.add(e);
                    }
                }
                if (bucket.getValue().isEmpty()) mapIt.remove();
            }
        } finally {
            lock.unlock();
        }

        // Notifica fora do lock — evita deadlock no listener
        if (config.evictionListener != null)
            for (Entry e : evicted) config.evictionListener.onEvicted(e);
    }

    /**
     * Para o thread de eviction em background.
     * Chame em Activity.onDestroy() ou quando o pool não for mais necessário.
     */
    public void shutdown() {
        if (evictionTask != null) evictionTask.cancel(false);
        if (scheduler != null) scheduler.shutdown();
    }

    // -------------------------------------------------------------------------
    // Diagnóstico
    // -------------------------------------------------------------------------

    public int inUseCount() {
        lock.lock();
        try {
            return inUse.size();
        } finally {
            lock.unlock();
        }
    }

    public int idleCount() {
        lock.lock();
        try {
            return totalIdle();
        } finally {
            lock.unlock();
        }
    }

    public void releaseAll() {
        lock.lock();
        try {
            new ArrayList<>(inUse).forEach(this::release);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            inUse.forEach(e -> e.inUse = false);
            inUse.clear();
            idleByKey.clear();
            idleBySize.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "ByteArrayPool{inUse=" + inUse.size() + ", idle=" + totalIdle() + "}";
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private void markInUse(Entry e) {
        e.inUse = true;
        e.idleSince = 0;
        inUse.add(e);
    }

    private int totalIdle() {
        int n = idleBySize.size();
        for (List<Entry> b : idleByKey.values()) n += b.size();
        return n;
    }
}