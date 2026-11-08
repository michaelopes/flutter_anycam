package br.dev.michaellopes.flutter_anycam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class UsbPreviewSizeSelectorTest {

    @Test
    public void emptyListReturnsEmpty() {
        assertTrue(UsbPreviewSizeSelector.rank(null, 640, 480, 15).isEmpty());
        assertTrue(UsbPreviewSizeSelector.rank(Collections.emptyList(), 640, 480, 15).isEmpty());
    }

    @Test
    public void prefersMjpegOverUncompressedAtSameResolution() {
        List<UsbPreviewSizeSelector.Candidate> sizes = Arrays.asList(
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_UNCOMPRESSED, 640, 480, 30,
                        Arrays.asList(15, 30)
                ),
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 30,
                        Arrays.asList(15, 30)
                )
        );
        List<UsbPreviewSizeSelector.Selection> ranked =
                UsbPreviewSizeSelector.rank(sizes, 640, 480, 15);
        assertEquals(UsbPreviewSizeSelector.FRAME_MJPEG, ranked.get(0).type);
        assertEquals(15, ranked.get(0).fps);
        assertEquals(UsbPreviewSizeSelector.FRAME_UNCOMPRESSED, ranked.get(1).type);
    }

    @Test
    public void picksClosestResolutionWithinPreferredFormat() {
        List<UsbPreviewSizeSelector.Candidate> sizes = Arrays.asList(
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 1920, 1080, 30, Arrays.asList(30)
                ),
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 800, 600, 30, Arrays.asList(30)
                ),
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 30, Arrays.asList(30)
                )
        );
        List<UsbPreviewSizeSelector.Selection> ranked =
                UsbPreviewSizeSelector.rank(sizes, 700, 500, 30);
        assertEquals(640, ranked.get(0).width);
        assertEquals(480, ranked.get(0).height);
    }

    @Test
    public void choosesClosestFpsFromList() {
        UsbPreviewSizeSelector.Candidate c = new UsbPreviewSizeSelector.Candidate(
                UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 30,
                Arrays.asList(5, 10, 15, 30)
        );
        assertEquals(15, UsbPreviewSizeSelector.chooseFps(c, 14));
        assertEquals(5, UsbPreviewSizeSelector.chooseFps(c, 5));
        assertEquals(30, UsbPreviewSizeSelector.chooseFps(c, 28));
    }

    @Test
    public void fallsBackToDefaultFpsWhenListEmpty() {
        UsbPreviewSizeSelector.Candidate c = new UsbPreviewSizeSelector.Candidate(
                UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 24, Collections.emptyList()
        );
        assertEquals(24, UsbPreviewSizeSelector.chooseFps(c, 15));
    }

    @Test
    public void chooseFpsToleratesNullAndInvalidEntries() {
        UsbPreviewSizeSelector.Candidate c = new UsbPreviewSizeSelector.Candidate(
                UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 30,
                Arrays.asList(null, 0, -5, 15, 30)
        );
        assertEquals(15, UsbPreviewSizeSelector.chooseFps(c, 12));
    }

    @Test
    public void chooseFpsFallsBackWhenListHasOnlyInvalidEntries() {
        UsbPreviewSizeSelector.Candidate withDefault = new UsbPreviewSizeSelector.Candidate(
                UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 25, Arrays.asList(null, 0)
        );
        assertEquals(25, UsbPreviewSizeSelector.chooseFps(withDefault, 15));

        UsbPreviewSizeSelector.Candidate noDefault = new UsbPreviewSizeSelector.Candidate(
                UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 0, null
        );
        assertEquals(15, UsbPreviewSizeSelector.chooseFps(noDefault, 15));
    }

    @Test
    public void applyFirstReturnsFirstAcceptedInRankOrder() {
        List<UsbPreviewSizeSelector.Selection> ranked = Arrays.asList(
                sel(1920, 1080), sel(1280, 720), sel(640, 480)
        );
        List<Integer> attempted = new ArrayList<>();
        UsbPreviewSizeSelector.Selection chosen = UsbPreviewSizeSelector.applyFirst(ranked, s -> {
            attempted.add(s.width);
            return s.width == 1280;
        }, sel(320, 240));
        assertSame(ranked.get(1), chosen);
        assertEquals("stops at the first accepted size", Arrays.asList(1920, 1280), attempted);
    }

    @Test
    public void applyFirstTreatsThrowingApplierAsRejection() {
        List<UsbPreviewSizeSelector.Selection> ranked = Arrays.asList(sel(1920, 1080), sel(640, 480));
        UsbPreviewSizeSelector.Selection chosen = UsbPreviewSizeSelector.applyFirst(ranked, s -> {
            if (s.width == 1920) throw new IllegalArgumentException("invalid preview size");
            return true;
        }, null);
        assertSame(ranked.get(1), chosen);
    }

    @Test
    public void applyFirstFallsBackToNegotiatedSizeWhenListEmpty() {
        UsbPreviewSizeSelector.Selection current = sel(640, 480);
        AtomicInteger calls = new AtomicInteger();
        UsbPreviewSizeSelector.Selection chosen = UsbPreviewSizeSelector.applyFirst(
                Collections.emptyList(), s -> {
                    calls.incrementAndGet();
                    return true;
                }, current);
        assertSame(current, chosen);
        assertEquals("negotiated size is not re-applied", 0, calls.get());
    }

    @Test
    public void applyFirstFallsBackToNegotiatedSizeWhenAllRejected() {
        UsbPreviewSizeSelector.Selection current = sel(640, 480);
        UsbPreviewSizeSelector.Selection chosen = UsbPreviewSizeSelector.applyFirst(
                Arrays.asList(sel(1920, 1080), sel(1280, 720)), s -> false, current);
        assertSame(current, chosen);
    }

    @Test
    public void applyFirstFailsWithoutAUsableNegotiatedSize() {
        assertNull(UsbPreviewSizeSelector.applyFirst(
                Collections.singletonList(sel(1920, 1080)), s -> false, null));
        assertNull(UsbPreviewSizeSelector.applyFirst(null, s -> true, sel(0, 480)));
    }

    private static UsbPreviewSizeSelector.Selection sel(int w, int h) {
        return new UsbPreviewSizeSelector.Selection(UsbPreviewSizeSelector.FRAME_MJPEG, w, h, 30);
    }

    @Test
    public void skipsInvalidDimensions() {
        List<UsbPreviewSizeSelector.Candidate> sizes = Arrays.asList(
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 0, 480, 30, Arrays.asList(30)
                ),
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_MJPEG, 640, 480, 30, Arrays.asList(30)
                )
        );
        List<UsbPreviewSizeSelector.Selection> ranked =
                UsbPreviewSizeSelector.rank(sizes, 640, 480, 30);
        assertEquals(1, ranked.size());
        assertEquals(640, ranked.get(0).width);
    }

    @Test
    public void uncompressedOnlyStillRanks() {
        List<UsbPreviewSizeSelector.Candidate> sizes = Collections.singletonList(
                new UsbPreviewSizeSelector.Candidate(
                        UsbPreviewSizeSelector.FRAME_UNCOMPRESSED, 320, 240, 15,
                        Arrays.asList(15)
                )
        );
        List<UsbPreviewSizeSelector.Selection> ranked =
                UsbPreviewSizeSelector.rank(sizes, 640, 480, 15);
        assertEquals(1, ranked.size());
        assertEquals(UsbPreviewSizeSelector.FRAME_UNCOMPRESSED, ranked.get(0).type);
    }
}
