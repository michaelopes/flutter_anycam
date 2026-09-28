package br.dev.michaellopes.flutter_anycam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure size/format/fps ranking for UVCAndroid {@code Size} descriptors.
 * {@code type} uses lib constants {@code UVC_VS_FRAME_MJPEG} (7) and
 * {@code UVC_VS_FRAME_UNCOMPRESSED} (5) — not {@code FRAME_FORMAT_*}.
 */
final class UsbPreviewSizeSelector {

    /** Matches {@code UVCCamera.UVC_VS_FRAME_MJPEG}. */
    static final int FRAME_MJPEG = 7;
    /** Matches {@code UVCCamera.UVC_VS_FRAME_UNCOMPRESSED}. */
    static final int FRAME_UNCOMPRESSED = 5;

    static final class Candidate {
        final int type;
        final int width;
        final int height;
        final int defaultFps;
        final List<Integer> fpsList;

        Candidate(int type, int width, int height, int defaultFps, List<Integer> fpsList) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.defaultFps = defaultFps;
            this.fpsList = fpsList != null ? fpsList : Collections.emptyList();
        }
    }

    static final class Selection {
        final int type;
        final int width;
        final int height;
        final int fps;

        Selection(int type, int width, int height, int fps) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    /** Applies a selection to the device; returns false (or throws) when rejected. */
    interface Applier {
        boolean apply(Selection selection) throws Exception;
    }

    private UsbPreviewSizeSelector() {
    }

    /**
     * First ranked selection the device accepts. When the list is empty or every entry is
     * rejected, falls back to {@code current} — the size {@code UVCCamera.open} already
     * negotiated. {@code setPreviewSize} validates against the supported list, so asking
     * for an explicit default with an empty list always fails.
     */
    static Selection applyFirst(List<Selection> ranked, Applier applier, Selection current) {
        if (ranked != null) {
            for (Selection sel : ranked) {
                try {
                    if (applier.apply(sel)) {
                        return sel;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (current != null && current.width > 0 && current.height > 0) {
            return current;
        }
        return null;
    }

    /**
     * Ranked candidates: MJPEG preferred over YUYV/uncompressed, then closest to
     * preferred resolution, with fps coerced toward {@code targetFps}.
     * Empty input → empty list (caller applies 640x480 fallback).
     */
    static List<Selection> rank(List<Candidate> sizes, int preferredW, int preferredH, int targetFps) {
        if (sizes == null || sizes.isEmpty()) {
            return Collections.emptyList();
        }
        int fps = Math.max(1, targetFps);

        List<Candidate> mjpeg = new ArrayList<>();
        List<Candidate> other = new ArrayList<>();
        for (Candidate c : sizes) {
            if (c.width <= 0 || c.height <= 0) continue;
            if (c.type == FRAME_MJPEG) {
                mjpeg.add(c);
            } else {
                other.add(c);
            }
        }

        Comparator<Candidate> byProximity = (a, b) -> {
            int da = Math.abs(a.width - preferredW) + Math.abs(a.height - preferredH);
            int db = Math.abs(b.width - preferredW) + Math.abs(b.height - preferredH);
            if (da != db) return Integer.compare(da, db);
            // Prefer larger when equally close (more detail).
            return Integer.compare(b.width * b.height, a.width * a.height);
        };
        mjpeg.sort(byProximity);
        other.sort(byProximity);

        List<Selection> ranked = new ArrayList<>(mjpeg.size() + other.size());
        for (Candidate c : mjpeg) {
            ranked.add(new Selection(c.type, c.width, c.height, chooseFps(c, fps)));
        }
        for (Candidate c : other) {
            ranked.add(new Selection(c.type, c.width, c.height, chooseFps(c, fps)));
        }
        return ranked;
    }

    static int chooseFps(Candidate c, int targetFps) {
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (Integer f : c.fpsList) {
            if (f == null || f <= 0) continue;
            int diff = Math.abs(f - targetFps);
            if (diff < bestDiff) {
                best = f;
                bestDiff = diff;
            }
        }
        if (best > 0) {
            return best;
        }
        if (c.defaultFps > 0) {
            return c.defaultFps;
        }
        return targetFps;
    }
}
