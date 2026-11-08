package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageMapperTargetSizeTest {

    @Test
    public void bothUnsetMeansCenteredSquare() {
        assertArrayEquals(new int[]{480, 480}, ImageMapperUtil.targetSize(-1, -1, 640, 480));
    }

    @Test
    public void singleUnsetDimensionKeepsAspect() {
        assertArrayEquals(new int[]{320, 240}, ImageMapperUtil.targetSize(320, -1, 640, 480));
        assertArrayEquals(new int[]{320, 240}, ImageMapperUtil.targetSize(-1, 240, 640, 480));
    }

    @Test
    public void dimensionsAreEvenForNv21() {
        assertArrayEquals(new int[]{320, 240}, ImageMapperUtil.targetSize(321, 241, 640, 480));
        assertArrayEquals(new int[]{2, 2}, ImageMapperUtil.targetSize(1, 1, 640, 480));
    }

    @Test
    public void everyRequestYieldsAValidBuffer() {
        int[][] sources = {{640, 480}, {1280, 720}, {639, 479}, {2, 2}};
        int[] requests = {-1, 0, 1, 3, 99, 224, 320, 4096};
        for (int[] src : sources) {
            for (int w : requests) {
                for (int h : requests) {
                    int[] out = ImageMapperUtil.targetSize(w, h, src[0], src[1]);
                    assertEquals(2, out.length);
                    assertTrue(out[0] >= 2 && out[1] >= 2);
                    assertEquals(0, out[0] % 2);
                    assertEquals(0, out[1] % 2);
                }
            }
        }
    }
}
