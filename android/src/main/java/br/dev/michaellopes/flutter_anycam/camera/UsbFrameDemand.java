package br.dev.michaellopes.flutter_anycam.camera;

/**
 * Pure "do we need this USB frame?" decision before copying out of the native buffer.
 */
final class UsbFrameDemand {

    private UsbFrameDemand() {
    }

    static boolean wantsFrame(
            boolean disposed,
            boolean frameDeliveryEnabled,
            boolean deliveryFrameDue,
            boolean streamFrameDue
    ) {
        if (disposed) {
            return false;
        }
        return (frameDeliveryEnabled && deliveryFrameDue) || streamFrameDue;
    }

    static boolean needsFrameCallback(boolean frameDeliveryEnabled, boolean hasActiveSink) {
        return frameDeliveryEnabled || hasActiveSink;
    }
}
