package br.dev.michaellopes.flutter_anycam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** convertNv21ToJpeg crop mapping: displayed (rotated) crop to raw sensor coordinates. */
public class FlutterAnycamPluginCropTest {

    private static final int W = 640;
    private static final int H = 480;

    @Test
    public void rotation0IsIdentity() {
        assertArrayEquals(new int[]{10, 20, 100, 50}, FlutterAnycamPlugin.rawCropRect(10, 20, 100, 50, W, H, 0));
    }

    @Test
    public void rotation90MapsAndSwaps() {
        assertArrayEquals(new int[]{0, 380, 50, 100}, FlutterAnycamPlugin.rawCropRect(0, 0, 100, 50, W, H, 90));
    }

    @Test
    public void rotation180Mirrors() {
        assertArrayEquals(new int[]{530, 410, 100, 50}, FlutterAnycamPlugin.rawCropRect(10, 20, 100, 50, W, H, 180));
    }

    @Test
    public void rotation270MapsAndSwaps() {
        assertArrayEquals(new int[]{590, 0, 50, 100}, FlutterAnycamPlugin.rawCropRect(0, 0, 100, 50, W, H, 270));
    }

    @Test
    public void oddOffsetsAreAlignedForChroma() {
        assertArrayEquals(new int[]{10, 20, 102, 52}, FlutterAnycamPlugin.rawCropRect(11, 21, 101, 51, W, H, 0));
    }

    @Test
    public void cropIsClampedToTheFrame() {
        assertArrayEquals(new int[]{600, 400, 40, 80}, FlutterAnycamPlugin.rawCropRect(600, 400, 100, 100, W, H, 0));
        assertArrayEquals(new int[]{0, 0, 50, 50}, FlutterAnycamPlugin.rawCropRect(-50, -50, 100, 100, W, H, 0));
    }

    @Test
    public void cropOutsideTheFrameIsRejected() {
        assertNull(FlutterAnycamPlugin.rawCropRect(700, 0, 10, 10, W, H, 0));
        assertNull(FlutterAnycamPlugin.rawCropRect(0, 0, 0, 0, W, H, 0));
        assertNull(FlutterAnycamPlugin.rawCropRect(0, 0, 1, 1, W, H, 0));
    }

    @Test
    public void randomCropsNeverLeaveTheBuffer() {
        Random random = new Random(42);
        int[][] frames = {{640, 480}, {1280, 720}, {639, 479}};
        int[] rotations = {0, 90, 180, 270};
        for (int i = 0; i < 20000; i++) {
            int[] f = frames[random.nextInt(frames.length)];
            int rotation = rotations[random.nextInt(rotations.length)];
            int left = random.nextInt(1600) - 300;
            int top = random.nextInt(1600) - 300;
            int cw = random.nextInt(1400) - 100;
            int ch = random.nextInt(1400) - 100;
            int[] r = FlutterAnycamPlugin.rawCropRect(left, top, cw, ch, f[0], f[1], rotation);
            if (r == null) continue;
            assertTrue(r[0] >= 0 && r[1] >= 0);
            assertTrue(r[2] >= 2 && r[3] >= 2);
            assertTrue(r[0] + r[2] <= f[0]);
            assertTrue(r[1] + r[3] <= f[1]);
            assertEquals(0, r[0] % 2);
            assertEquals(0, r[1] % 2);
            assertEquals(0, r[2] % 2);
            assertEquals(0, r[3] % 2);
        }
    }

    @Test
    public void evenClampForResize() {
        assertEquals(100, FlutterAnycamPlugin.evenClamp(101, 640));
        assertEquals(640, FlutterAnycamPlugin.evenClamp(700, 640));
        assertEquals(0, FlutterAnycamPlugin.evenClamp(1, 640));
        assertEquals(0, FlutterAnycamPlugin.evenClamp(-1, 640));
        assertEquals(2, FlutterAnycamPlugin.evenClamp(3, 2));
    }
}
