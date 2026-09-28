package br.dev.michaellopes.flutter_anycam.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At most one frame in flight toward a slow consumer (Flutter main thread, WebRTC encoder).
 *
 * <p>Producers check {@link #isIdle()} before doing any conversion work, then claim the slot
 * with {@link #tryBegin}. The slot frees once every fan-out send has completed, so queues
 * downstream can never hold more than one frame per gate — and reusable buffers referenced
 * by that frame are never overwritten while it is still queued.</p>
 */
public final class FrameDeliveryGate {

    private final AtomicBoolean busy = new AtomicBoolean(false);

    public boolean isIdle() {
        return !busy.get();
    }

    /**
     * Claims the slot for a frame sent to {@code sends} consumers.
     *
     * @param onNoneDelivered run once when no consumer received the frame (e.g. to free a
     *                        native frame nobody will close); may be null.
     * @return null when a frame is already in flight — the caller must drop its frame and
     * run its own cleanup.
     */
    public Delivery tryBegin(int sends, Runnable onNoneDelivered) {
        if (sends <= 0) {
            if (onNoneDelivered != null) onNoneDelivered.run();
            return null;
        }
        if (!busy.compareAndSet(false, true)) {
            return null;
        }
        return new Delivery(sends, onNoneDelivered);
    }

    public final class Delivery {
        private final AtomicInteger remaining;
        private final AtomicBoolean anyDelivered = new AtomicBoolean(false);
        private final Runnable onNoneDelivered;

        private Delivery(int sends, Runnable onNoneDelivered) {
            this.remaining = new AtomicInteger(sends);
            this.onNoneDelivered = onNoneDelivered;
        }

        /** Call exactly once per send, from any thread. Extra calls are ignored. */
        public void complete(boolean delivered) {
            if (delivered) {
                anyDelivered.set(true);
            }
            int left = remaining.decrementAndGet();
            if (left != 0) {
                return;
            }
            busy.set(false);
            if (!anyDelivered.get() && onNoneDelivered != null) {
                onNoneDelivered.run();
            }
        }
    }
}
