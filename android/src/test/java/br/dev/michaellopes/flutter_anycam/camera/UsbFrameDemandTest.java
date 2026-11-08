package br.dev.michaellopes.flutter_anycam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbFrameDemandTest {

    @Test
    public void disposedNeverWantsFrame() {
        assertFalse(UsbFrameDemand.wantsFrame(true, true, true, true));
    }

    @Test
    public void deliveryOnlyWhenEnabledAndDue() {
        assertTrue(UsbFrameDemand.wantsFrame(false, true, true, false));
        assertFalse(UsbFrameDemand.wantsFrame(false, true, false, false));
        assertFalse(UsbFrameDemand.wantsFrame(false, false, true, false));
    }

    @Test
    public void streamDueAloneIsEnough() {
        assertTrue(UsbFrameDemand.wantsFrame(false, false, false, true));
        assertTrue(UsbFrameDemand.wantsFrame(false, true, false, true));
    }

    @Test
    public void needsFrameCallbackMatchesConsumers() {
        assertTrue(UsbFrameDemand.needsFrameCallback(true, false));
        assertTrue(UsbFrameDemand.needsFrameCallback(false, true));
        assertTrue(UsbFrameDemand.needsFrameCallback(true, true));
        assertFalse(UsbFrameDemand.needsFrameCallback(false, false));
    }
}
