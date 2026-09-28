package br.dev.michaellopes.flutter_anycam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UsbCameraIdTest {

    private static UsbCameraId.Device dev(int deviceId, int vid, int pid, String serial) {
        return new UsbCameraId.Device(deviceId, vid, pid, serial);
    }

    @Test
    public void singleCameraIdDoesNotDependOnDeviceNumberOrSerial() {
        List<String> before = UsbCameraId.assign(Collections.singletonList(dev(1002, 0x046d, 0x0825, null)));
        List<String> after = UsbCameraId.assign(Collections.singletonList(dev(1007, 0x046d, 0x0825, "ABC")));
        assertEquals("usb:046d:0825", before.get(0));
        assertEquals("replug + permission must not change the id", before, after);
    }

    @Test
    public void replugKeepsMatchingTheSameCamera() {
        String id = UsbCameraId.assign(Collections.singletonList(dev(1002, 0x046d, 0x0825, null))).get(0);
        assertTrue(UsbCameraId.matches(id, dev(1002, 0x046d, 0x0825, null)));
        assertTrue("new kernel device number after bad contact", UsbCameraId.matches(id, dev(1003, 0x046d, 0x0825, null)));
        assertFalse("other model", UsbCameraId.matches(id, dev(1003, 0x046d, 0x0826, null)));
        assertFalse("other vendor", UsbCameraId.matches(id, dev(1003, 0x1234, 0x0825, null)));
    }

    @Test
    public void identicalModelsUseUniqueSerials() {
        List<String> ids = UsbCameraId.assign(Arrays.asList(
                dev(10, 0x0c45, 0x6366, "SN-A"),
                dev(11, 0x0c45, 0x6366, "SN-B")
        ));
        assertEquals("usb:0c45:6366:SN-A", ids.get(0));
        assertEquals("usb:0c45:6366:SN-B", ids.get(1));

        assertTrue(UsbCameraId.matches(ids.get(0), dev(20, 0x0c45, 0x6366, "SN-A")));
        assertFalse(UsbCameraId.matches(ids.get(0), dev(21, 0x0c45, 0x6366, "SN-B")));
        assertTrue("serial hidden before permission", UsbCameraId.matches(ids.get(0), dev(21, 0x0c45, 0x6366, null)));
        assertNull(UsbCameraId.preferredDeviceId(ids.get(0)));
    }

    @Test
    public void identicalModelsWithoutSerialFallBackToDeviceNumber() {
        List<String> ids = UsbCameraId.assign(Arrays.asList(
                dev(10, 0x0c45, 0x6366, null),
                dev(11, 0x0c45, 0x6366, null),
                dev(12, 0x046d, 0x0825, null)
        ));
        assertEquals("usb:0c45:6366#10", ids.get(0));
        assertEquals("usb:0c45:6366#11", ids.get(1));
        assertEquals("usb:046d:0825", ids.get(2));

        assertEquals(Integer.valueOf(10), UsbCameraId.preferredDeviceId(ids.get(0)));
        assertTrue("after replug any same model may be taken", UsbCameraId.matches(ids.get(0), dev(15, 0x0c45, 0x6366, null)));
        assertFalse(UsbCameraId.matches(ids.get(0), dev(15, 0x046d, 0x0825, null)));
    }

    @Test
    public void duplicateSerialsAreNotTrustedForIdentity() {
        List<String> ids = UsbCameraId.assign(Arrays.asList(
                dev(10, 0x0c45, 0x6366, "0000"),
                dev(11, 0x0c45, 0x6366, "0000")
        ));
        assertEquals("usb:0c45:6366#10", ids.get(0));
        assertEquals("usb:0c45:6366#11", ids.get(1));
    }

    @Test
    public void serialMayContainSeparators() {
        String id = UsbCameraId.assign(Arrays.asList(
                dev(10, 0x0c45, 0x6366, "a:b#c"),
                dev(11, 0x0c45, 0x6366, "x")
        )).get(0);
        assertEquals("usb:0c45:6366:a:b#c", id);
        assertTrue(UsbCameraId.matches(id, dev(30, 0x0c45, 0x6366, "a:b#c")));
        assertFalse(UsbCameraId.matches(id, dev(30, 0x0c45, 0x6366, "a:b")));
    }

    @Test
    public void legacyNumericIdStillMatchesExactDevice() {
        assertTrue(UsbCameraId.matches("1002", dev(1002, 0x046d, 0x0825, null)));
        assertFalse(UsbCameraId.matches("1002", dev(1003, 0x046d, 0x0825, null)));
        assertEquals(Integer.valueOf(1002), UsbCameraId.preferredDeviceId("1002"));
    }

    @Test
    public void emptySerialIsTreatedAsMissing() {
        assertNull(dev(1, 1, 1, "").serial);
    }

    @Test
    public void rejectsGarbage() {
        UsbCameraId.Device d = dev(1, 0x046d, 0x0825, null);
        assertFalse(UsbCameraId.matches(null, d));
        assertFalse(UsbCameraId.matches("", d));
        assertFalse(UsbCameraId.matches("usb:46d:825", d));
        assertFalse(UsbCameraId.matches("rtsp://host", d));
        assertFalse(UsbCameraId.matches("usb:046d:0825", null));
        assertNull(UsbCameraId.preferredDeviceId("usb:046d:0825"));
        assertNull(UsbCameraId.preferredDeviceId(null));
        assertNull("overflowing legacy id", UsbCameraId.preferredDeviceId("99999999999999"));
    }

    @Test
    public void vendorAndProductAreMaskedToSixteenBits() {
        assertEquals("usb:ffff:0001", UsbCameraId.assign(Collections.singletonList(dev(1, 0xffff, 1, null))).get(0));
    }
}
