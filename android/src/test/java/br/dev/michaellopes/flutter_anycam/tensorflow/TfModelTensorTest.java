package br.dev.michaellopes.flutter_anycam.tensorflow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tensorflow.lite.DataType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class TfModelTensorTest {

    private static final float EPS = 1e-6f;

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    @Test
    public void float32IsCopiedVerbatim() {
        ByteBuffer b = buffer(4 * 3);
        b.putFloat(1.5f).putFloat(-2f).putFloat(0.25f).rewind();
        float[] out = TfModelHandler.readTensor(b, new TfModelHandler.TensorMeta(DataType.FLOAT32, 0f, 0), new int[]{1, 3});
        assertArrayEquals(new float[]{1.5f, -2f, 0.25f}, out, EPS);
    }

    @Test
    public void uint8IsUnsignedAndDequantized() {
        ByteBuffer b = buffer(3);
        b.put((byte) 0xFF).put((byte) 128).put((byte) 0).rewind();
        float[] out = TfModelHandler.readTensor(b, new TfModelHandler.TensorMeta(DataType.UINT8, 0.5f, 128), new int[]{3});
        // 0xFF must read as 255, not -1 (sign extension would give a negative score).
        assertArrayEquals(new float[]{63.5f, 0f, -64f}, out, EPS);
    }

    @Test
    public void int8IsSignedAndDequantized() {
        ByteBuffer b = buffer(2);
        b.put((byte) -128).put((byte) 127).rewind();
        float[] out = TfModelHandler.readTensor(b, new TfModelHandler.TensorMeta(DataType.INT8, 0.1f, -128), new int[]{2});
        assertArrayEquals(new float[]{0f, 25.5f}, out, 1e-4f);
    }

    @Test
    public void int32AndInt64AreReadWithTheirWidth() {
        ByteBuffer i32 = buffer(4 * 2);
        i32.putInt(7).putInt(-3).rewind();
        assertArrayEquals(new float[]{7f, -3f},
                TfModelHandler.readTensor(i32, new TfModelHandler.TensorMeta(DataType.INT32, 0f, 0), new int[]{2}), EPS);

        ByteBuffer i64 = buffer(8 * 2);
        i64.putLong(42L).putLong(-1L).rewind();
        assertArrayEquals(new float[]{42f, -1f},
                TfModelHandler.readTensor(i64, new TfModelHandler.TensorMeta(DataType.INT64, 0f, 0), new int[]{2}), EPS);
    }

    @Test
    public void zeroScaleMeansUnquantized() {
        TfModelHandler.TensorMeta meta = new TfModelHandler.TensorMeta(DataType.UINT8, 0f, 0);
        assertEquals(1f, meta.scale, EPS);
        assertEquals(1f, meta.invScale, EPS);
    }

    @Test
    public void reshapeBuildsNestedListsWithFloatLeaves() {
        float[] flat = {0, 1, 2, 3, 4, 5};
        Object nested = TfModelHandler.reshape(flat, new int[]{2, 3}, 0);
        assertTrue(nested instanceof List);
        List<?> rows = (List<?>) nested;
        assertEquals(2, rows.size());
        assertArrayEquals(new float[]{0, 1, 2}, (float[]) rows.get(0), EPS);
        assertArrayEquals(new float[]{3, 4, 5}, (float[]) rows.get(1), EPS);

        Object deep = TfModelHandler.reshape(flat, new int[]{1, 2, 3}, 0);
        List<?> outer = (List<?>) deep;
        assertEquals(1, outer.size());
        assertEquals(2, ((List<?>) outer.get(0)).size());
    }

    @Test
    public void reshapeHandlesScalarsAndVectors() {
        assertArrayEquals(new float[]{9f}, (float[]) TfModelHandler.reshape(new float[]{9f}, new int[]{}, 0), EPS);
        assertArrayEquals(new float[]{1f, 2f}, (float[]) TfModelHandler.reshape(new float[]{1f, 2f}, new int[]{2}, 0), EPS);
    }
}
