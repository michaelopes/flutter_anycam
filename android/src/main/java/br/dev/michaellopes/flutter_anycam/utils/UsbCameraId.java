package br.dev.michaellopes.flutter_anycam.utils;

import android.hardware.usb.UsbDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Camera ids for USB/UVC devices that survive a replug.
 *
 * <p>{@link UsbDevice#getDeviceId()} is the kernel device number: it changes on every
 * enumeration (a loose cable is a detach + attach), so it cannot identify a camera.</p>
 *
 * <ul>
 *   <li>{@code usb:vvvv:pppp} — the only device with that vendor/product pair.</li>
 *   <li>{@code usb:vvvv:pppp:SERIAL} — identical models told apart by a readable serial.</li>
 *   <li>{@code usb:vvvv:pppp#DEVICEID} — identical models without a usable serial. The suffix
 *   only picks the device at open time; after a replug any unclaimed same-model device matches.</li>
 *   <li>A bare number is the legacy {@code getDeviceId()} id and matches exactly.</li>
 * </ul>
 *
 * <p>Serials are left out when not needed: before USB permission Android 10+ hides them, so
 * an id that depended on them would change once permission is granted.</p>
 */
public final class UsbCameraId {

    private static final Pattern ID_PATTERN =
            Pattern.compile("^usb:([0-9a-f]{4}):([0-9a-f]{4})(?::(.+)|#(\\d+))?$", Pattern.DOTALL);
    private static final Pattern LEGACY_PATTERN = Pattern.compile("^\\d+$");

    private UsbCameraId() {
    }

    public static final class Device {
        public final int deviceId;
        public final int vendorId;
        public final int productId;
        /** Null when absent or not readable without permission. */
        public final String serial;

        public Device(int deviceId, int vendorId, int productId, String serial) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.serial = serial == null || serial.isEmpty() ? null : serial;
        }
    }

    public static Device of(UsbDevice device) {
        String serial = null;
        try {
            serial = device.getSerialNumber();
        } catch (SecurityException ignored) {
        }
        return new Device(device.getDeviceId(), device.getVendorId(), device.getProductId(), serial);
    }

    /** Ids for a snapshot of attached cameras, in input order. */
    public static List<String> assign(List<Device> devices) {
        Map<String, List<Device>> byModel = new HashMap<>();
        for (Device d : devices) {
            String model = modelKey(d);
            List<Device> group = byModel.get(model);
            if (group == null) {
                group = new ArrayList<>();
                byModel.put(model, group);
            }
            group.add(d);
        }

        List<String> ids = new ArrayList<>(devices.size());
        for (Device d : devices) {
            String model = modelKey(d);
            List<Device> group = byModel.get(model);
            if (group.size() == 1) {
                ids.add(model);
            } else if (d.serial != null && isUniqueSerial(group, d.serial)) {
                ids.add(model + ":" + d.serial);
            } else {
                ids.add(model + "#" + d.deviceId);
            }
        }
        return ids;
    }

    public static boolean matches(String cameraId, Device device) {
        if (cameraId == null || device == null) return false;
        if (LEGACY_PATTERN.matcher(cameraId).matches()) {
            return cameraId.equals(String.valueOf(device.deviceId));
        }
        Matcher m = ID_PATTERN.matcher(cameraId);
        if (!m.matches()) return false;
        if (Integer.parseInt(m.group(1), 16) != device.vendorId
                || Integer.parseInt(m.group(2), 16) != device.productId) {
            return false;
        }
        String serial = m.group(3);
        return serial == null || device.serial == null || serial.equals(device.serial);
    }

    /** Device number the id prefers while that device is attached, or null. */
    public static Integer preferredDeviceId(String cameraId) {
        if (cameraId == null) return null;
        if (LEGACY_PATTERN.matcher(cameraId).matches()) {
            try {
                return Integer.parseInt(cameraId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher m = ID_PATTERN.matcher(cameraId);
        if (m.matches() && m.group(4) != null) {
            try {
                return Integer.parseInt(m.group(4));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String modelKey(Device d) {
        return String.format(Locale.US, "usb:%04x:%04x", d.vendorId & 0xffff, d.productId & 0xffff);
    }

    private static boolean isUniqueSerial(List<Device> group, String serial) {
        int count = 0;
        for (Device other : group) {
            if (serial.equals(other.serial)) count++;
        }
        return count == 1;
    }
}
