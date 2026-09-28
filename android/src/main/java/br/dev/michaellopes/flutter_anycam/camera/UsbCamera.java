package br.dev.michaellopes.flutter_anycam.camera;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.UsbCameraId;
import br.dev.michaellopes.flutter_anycam.utils.UsbFrameBufferPool;
import io.flutter.view.TextureRegistry;

/**
 * USB/UVC camera with the same widget contract as {@link DeviceCamera} / {@link RTSPCamera}.
 *
 * <p>Threads:</p>
 * <ul>
 *   <li>USBMonitor listener callbacks arrive on the main thread and only dispatch.</li>
 *   <li>Every {@link UVCCamera} call runs on {@link #USB_WORKER}. {@code UVCCamera} methods
 *   such as {@code setFrameCallback} are not synchronized against {@code destroy}, and a
 *   device must be fully released before another instance can claim it — one serial
 *   thread for all USB cameras guarantees both.</li>
 *   <li>{@link IFrameCallback#onFrame} runs on the library's native capture thread — copy
 *   out of that buffer immediately (or skip under backpressure) and never block it.</li>
 *   <li>Lifecycle events are emitted on the main thread through {@link CameraEventGate}.</li>
 * </ul>
 *
 * <p>Reconnect: the instance binds to one physical device at a time. A replug re-enumerates
 * the device under a new {@code deviceName}/{@code deviceId}; USBMonitor reports it through
 * {@link #onAttach}, which rebinds while the view stays alive and starts a new event session,
 * so the widget receives {@code onConnected} again after {@code onDisconnected}.</p>
 */
public class UsbCamera extends BaseCamera implements IFrameCallback, USBMonitor.OnDeviceConnectListener {

    private static final String TAG = "UsbCamera";
    private static final int FRAME_POOL_SIZE = 2;
    private static final long ANALYSIS_SHUTDOWN_TIMEOUT_MS = 500;
    /** A flaky contact often fails the first open right after re-enumeration. */
    private static final int MAX_OPEN_RETRIES = 2;
    private static final long OPEN_RETRY_DELAY_MS = 1000;
    /** libuvc can block in stop/close when the device vanished mid-transfer. */
    private static final long UVC_DESTROY_TIMEOUT_MS = 3000;
    private static final long REBIND_CHECK_DELAY_MS = 500;

    private static final ExecutorService USB_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UsbWorker");
        t.setDaemon(true);
        return t;
    });

    /** Runs UVCCamera.destroy so a hung native close cannot stall {@link #USB_WORKER}. */
    private static final ExecutorService USB_REAPER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UsbReaper");
        t.setDaemon(true);
        return t;
    });

    /** Device names bound by some instance; keeps identical models from sharing a device. */
    private static final Set<String> CLAIMED_DEVICES = new HashSet<>();

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final CameraEventGate events = new CameraEventGate();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "UsbAnalysis")
    );
    private final UsbFrameBufferPool framePool = new UsbFrameBufferPool(FRAME_POOL_SIZE);

    /** FPS gate for widget frame delivery. Raw/WebRTC sinks have their own limiters. */
    private final FrameRateLimiterUtil<Object> deliveryLimiter =
            new FrameRateLimiterUtil<Object>(getFps()) {
                @Override
                protected void onFrameLimited(Object ignored) {
                }
            };

    private volatile USBMonitor mUSBMonitor;
    /** Confined to the main thread. */
    private UsbDevice boundDevice;
    /** Confined to the main thread. */
    private int boundSession;
    /** Confined to the main thread. */
    private int openAttempts;
    /** Session of {@link #boundDevice}, or -1 while unbound; read by {@link #USB_WORKER}. */
    private volatile int activeSession = -1;
    /** Confined to {@link #USB_WORKER}. */
    private UVCCamera mUVCCamera;
    /** Confined to {@link #USB_WORKER}. */
    private Surface previewSurface;
    /** Confined to {@link #USB_WORKER}. */
    private boolean frameCallbackRegistered = false;
    /** Immutable per open session; read by the native frame thread. */
    private volatile Session session;

    private final CameraStreamManager.SinkChangeListener sinkListener = cameraId -> {
        if (disposed.get()) return;
        if (cameraId != null && cameraId.equals(getCameraId())) {
            runOnUsbWorker(this::syncFrameCallback);
        }
    };

    public UsbCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
    }

    @Override
    public void init() {
        if (disposed.get()) return;

        CameraStreamManager.getInstance().addSinkChangeListener(sinkListener);

        USBMonitor monitor = new USBMonitor(ContextUtil.get(), this);
        mUSBMonitor = monitor;
        monitor.register();
    }

    // ========= USBMonitor (main thread) =========

    @Override
    public void onAttach(UsbDevice device) {
        if (disposed.get() || events.isDisposed()) return;
        USBMonitor monitor = mUSBMonitor;
        if (monitor == null || boundDevice != null || !isMatchingDevice(device)) return;
        if (yieldsToPreferredDevice(device)) return;
        if (!claimDevice(device.getDeviceName())) return;

        boundDevice = device;
        boundSession = events.beginSession();
        activeSession = boundSession;
        openAttempts = 0;
        Log.d(TAG, "bind " + device.getDeviceName() + " session=" + boundSession);

        // requestPermission opens immediately when already granted; otherwise the system
        // dialog runs and we get onDeviceOpen / onCancel asynchronously. Do NOT call
        // hasPermission right after requestPermission and treat a miss as unauthorized.
        monitor.requestPermission(device);
    }

    @Override
    public void onDetach(UsbDevice device) {
        if (!isBoundDevice(device)) return;
        final int s = boundSession;
        unbind();
        tearDownThenEmit("detach", s, this::onDisconnected);
    }

    @Override
    public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        if (disposed.get() || events.isDisposed()) return;
        if (!isBoundDevice(device)) return;
        final int s = boundSession;
        runOnUsbWorker(() -> openAndStart(ctrlBlock, s));
    }

    @Override
    public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (!isBoundDevice(device)) return;
        final int s = boundSession;
        unbind();
        tearDownThenEmit("device-close", s, this::onDisconnected);
        // On unplug onDetach follows and the device is gone; if it is still attached,
        // USBMonitor will not report it again, so rebind here.
        mainHandler.postDelayed(this::rebindIfPresent, REBIND_CHECK_DELAY_MS);
    }

    @Override
    public void onCancel(UsbDevice device) {
        if (!isBoundDevice(device)) return;
        // Permission denied before a successful open.
        final int s = boundSession;
        unbind();
        tearDownThenEmit("cancel", s, this::onUnauthorized);
    }

    @Override
    public void onError(UsbDevice device, USBMonitor.USBException e) {
        if (boundDevice == null) return;
        if (device != null && !isBoundDevice(device)) return;
        final String message = e != null ? e.getMessage() : "USB error";
        onOpenFailed(boundSession, message);
    }

    /** Main thread. Retries a fresh open a few times before reporting the failure. */
    private void onOpenFailed(int s, String message) {
        if (disposed.get() || boundDevice == null || s != boundSession) return;
        final UsbDevice device = boundDevice;
        if (openAttempts < MAX_OPEN_RETRIES) {
            openAttempts++;
            Log.w(TAG, "USB open failed (" + message + "), retry " + openAttempts + "/" + MAX_OPEN_RETRIES);
            mainHandler.postDelayed(() -> {
                USBMonitor monitor = mUSBMonitor;
                if (disposed.get() || monitor == null || boundDevice != device || boundSession != s) return;
                monitor.requestPermission(device);
            }, OPEN_RETRY_DELAY_MS);
            return;
        }
        unbind();
        tearDownThenEmit("open-failed", s, () -> onFailed(message));
    }

    private void rebindIfPresent() {
        if (disposed.get() || boundDevice != null) return;
        UsbManager usbManager = usbManager();
        if (usbManager == null) return;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            onAttach(device);
            if (boundDevice != null) return;
        }
    }

    /** Main thread. Idempotent. */
    private void unbind() {
        UsbDevice device = boundDevice;
        boundDevice = null;
        activeSession = -1;
        openAttempts = 0;
        if (device != null) {
            releaseDeviceClaim(device.getDeviceName());
        }
    }

    // ========= open / preview (USB_WORKER) =========

    private void openAndStart(USBMonitor.UsbControlBlock ctrlBlock, int s) {
        if (disposed.get() || activeSession != s) return;

        // A second onDeviceOpen for the same device replaces the current session.
        releaseUvc();

        final Session opened;
        try {
            opened = openUvc(ctrlBlock);
        } catch (Exception e) {
            Log.e(TAG, "USB open failed", e);
            releaseUvc();
            final String message = e.getMessage();
            mainHandler.post(() -> onOpenFailed(s, message));
            return;
        }

        if (disposed.get() || activeSession != s) {
            releaseUvc();
            return;
        }

        syncFrameCallback();

        final Map<String, Object> result = new HashMap<>();
        result.put("width", opened.width);
        result.put("height", opened.height);
        mainHandler.post(() -> {
            if (activeSession == s && events.onConnected(s)) {
                onConnected(result);
            }
        });
    }

    /** On failure the partially opened camera stays in {@link #mUVCCamera} for {@link #releaseUvc}. */
    private Session openUvc(USBMonitor.UsbControlBlock ctrlBlock) {
        UVCCamera camera = new UVCCamera(new UVCParam());
        int openResult = camera.open(ctrlBlock);
        if (openResult != 0) {
            destroyQuietly(camera, false);
            throw new IllegalStateException("UVCCamera.open failed: " + openResult);
        }
        mUVCCamera = camera;

        Session opened = applyPreviewSize(camera);
        if (opened == null) {
            throw new IllegalStateException("No usable USB preview size");
        }

        texture.surfaceTexture().setDefaultBufferSize(opened.width, opened.height);
        Surface surface = new Surface(texture.surfaceTexture());
        previewSurface = surface;
        camera.setPreviewDisplay(surface);
        camera.startPreview();

        session = opened;
        return opened;
    }

    private Session applyPreviewSize(UVCCamera camera) {
        List<Size> supported = camera.getSupportedSizeList();
        List<UsbPreviewSizeSelector.Candidate> candidates = new ArrayList<>();
        if (supported != null) {
            for (Size s : supported) {
                if (s == null) continue;
                candidates.add(new UsbPreviewSizeSelector.Candidate(
                        s.type, s.width, s.height, s.fps, s.fpsList
                ));
            }
        }

        List<UsbPreviewSizeSelector.Selection> ranked = UsbPreviewSizeSelector.rank(
                candidates,
                preferredSize.getWidth(),
                preferredSize.getHeight(),
                getFps()
        );

        Size negotiated = camera.getPreviewSize();
        UsbPreviewSizeSelector.Selection current = negotiated != null
                ? new UsbPreviewSizeSelector.Selection(
                        negotiated.type, negotiated.width, negotiated.height, negotiated.fps)
                : null;

        UsbPreviewSizeSelector.Selection chosen = UsbPreviewSizeSelector.applyFirst(ranked, sel -> {
            try {
                camera.setPreviewSize(sel.width, sel.height, sel.type, sel.fps);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "setPreviewSize rejected " + sel.width + "x" + sel.height
                        + " type=" + sel.type + " fps=" + sel.fps, e);
                return false;
            }
        }, current);

        if (chosen == null) {
            return null;
        }
        Log.d(TAG, "Preview " + chosen.width + "x" + chosen.height
                + " type=" + chosen.type + " fps=" + chosen.fps
                + (chosen == current ? " (negotiated by open)" : ""));
        return new Session(chosen.width, chosen.height, chosen.type, chosen.fps);
    }

    /** Register / clear the native NV21 callback based on current consumers. */
    private void syncFrameCallback() {
        UVCCamera camera = mUVCCamera;
        if (camera == null || disposed.get()) return;

        boolean needed = UsbFrameDemand.needsFrameCallback(
                frameDeliveryEnabled,
                CameraStreamManager.getInstance().hasActiveSink(getCameraId())
        );
        try {
            if (needed && !frameCallbackRegistered) {
                camera.setFrameCallback(this, UVCCamera.PIXEL_FORMAT_NV21);
                frameCallbackRegistered = true;
            } else if (!needed && frameCallbackRegistered) {
                camera.setFrameCallback(null, 0);
                frameCallbackRegistered = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "syncFrameCallback failed", e);
        }
    }

    // ========= frames (native capture thread) =========

    @Override
    public void onFrame(ByteBuffer frame) {
        if (frame == null || disposed.get()) return;

        Session s = session;
        if (s == null) return;

        final String cameraId = getCameraId();
        final CameraStreamManager streams = CameraStreamManager.getInstance();

        boolean canDeliver = canDeliverFrame();
        boolean deliveryDue = canDeliver && deliveryLimiter.isFrameDue();
        boolean streamDue = streams.isFrameDue(cameraId);
        if (!UsbFrameDemand.wantsFrame(disposed.get(), canDeliver, deliveryDue, streamDue)) {
            return;
        }

        // Decide before copy — native buffer is only valid for this callback.
        UsbFrameBufferPool.Frame pooled = framePool.copyOf(frame, s.width, s.height);
        if (pooled == null) {
            // Backpressure (analysis holds maxInFlight frames) or a truncated native frame.
            return;
        }

        // Claim the delivery slot only once the copy exists, so a queued frame is not
        // copied just to be dropped by the limiter on the analysis thread.
        final boolean deliver = deliveryDue && deliveryLimiter.shouldProcessFrame();

        try {
            analysisExecutor.execute(() -> processPooledFrame(pooled, deliver));
        } catch (RejectedExecutionException e) {
            pooled.release();
        }
    }

    private void processPooledFrame(UsbFrameBufferPool.Frame pooled, boolean deliverRequested) {
        try {
            if (disposed.get()) return;

            final int width = pooled.width;
            final int height = pooled.height;
            final Integer customRotation = getCustomRotationDegrees();
            final String cameraId = getCameraId();
            final CameraStreamManager streams = CameraStreamManager.getInstance();

            // Re-check: the previous frame may still be in flight to Flutter.
            boolean deliver = deliverRequested && canDeliverFrame();
            boolean streamDue = streams.isFrameDue(cameraId);
            boolean standard = isStandard();

            ByteBuffer nv21 = pooled.buffer;
            nv21.rewind();

            if (streamDue) {
                streams.sendNv21Frame(cameraId, nv21, width, height, 0, customRotation);
                nv21.rewind();
            }

            if (!deliver) return;

            Map<String, Object> frameMap;
            if (standard) {
                frameMap = imageAnalysisUtil.usbFrameToNV21Map(
                        nv21, width, height, resizeFrame, filter, customRotation
                );
            } else {
                frameMap = TfFrameHandler.getInstance().addFrame(
                        nv21, width, height, filter, customRotation
                );
            }

            if (frameMap != null && !disposed.get()) {
                onVideoFrameReceived(frameMap);
            }
        } catch (Throwable t) {
            // A bad frame must not tear down the session — only device events are terminal.
            Log.e(TAG, "processPooledFrame failed", t);
        } finally {
            pooled.release();
        }
    }

    // ========= controls / lifecycle =========

    @Override
    public void setZoom(float zoom) {
        if (disposed.get()) return;
        runOnUsbWorker(() -> {
            UVCCamera camera = mUVCCamera;
            if (camera == null || disposed.get()) return;
            try {
                if (!camera.isOpened() || camera.getControl() == null) return;
                int value = Math.round(zoom * 10);
                if (camera.getControl().isZoomAbsoluteEnable()) {
                    camera.getControl().setZoomAbsolute(value);
                } else {
                    camera.getControl().setZoomRelative(value);
                }
            } catch (Exception e) {
                Log.w(TAG, "setZoom failed", e);
            }
        });
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    /**
     * Returns immediately. Teardown runs on {@link #USB_WORKER} after any queued open, so a
     * new instance for the same device only opens once this one has released it:
     * drop the frame callback → destroy UVC → release Surface → drain analysis →
     * destroy USBMonitor → {@code super.dispose()} on the main thread (texture.release).
     */
    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) return;
        events.dispose();
        unbind();

        CameraStreamManager.getInstance().removeSinkChangeListener(sinkListener);

        final USBMonitor monitor = mUSBMonitor;
        mUSBMonitor = null;
        if (monitor != null) {
            try {
                if (monitor.isRegistered()) {
                    monitor.unregister();
                }
            } catch (Throwable t) {
                Log.w(TAG, "USBMonitor unregister failed", t);
            }
        }

        boolean queued = runOnUsbWorker(() -> {
            releaseUvc();
            analysisExecutor.shutdownNow();
            try {
                analysisExecutor.awaitTermination(ANALYSIS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (monitor != null) {
                try {
                    monitor.destroy();
                } catch (Throwable t) {
                    Log.w(TAG, "USBMonitor destroy failed", t);
                }
            }
            mainHandler.post(this::releaseBase);
        });
        if (!queued) {
            analysisExecutor.shutdownNow();
            mainHandler.post(this::releaseBase);
        }
    }

    private void releaseBase() {
        try {
            super.dispose();
        } catch (Throwable t) {
            Log.w(TAG, "Texture release failed", t);
        }
    }

    /** Stop UVC on the worker, then emit one terminal event for session {@code s}. */
    private void tearDownThenEmit(String reason, int s, Runnable emit) {
        boolean queued = runOnUsbWorker(() -> {
            Log.d(TAG, "tearDownUvc: " + reason);
            releaseUvc();
            emitTerminal(s, emit);
        });
        if (!queued) {
            emitTerminal(s, emit);
        }
    }

    private void emitTerminal(int s, Runnable emit) {
        mainHandler.post(() -> {
            if (events.onTerminal(s)) {
                emit.run();
            }
        });
    }

    /** USB_WORKER only. Idempotent. */
    private void releaseUvc() {
        session = null;
        UVCCamera camera = mUVCCamera;
        mUVCCamera = null;
        if (camera != null) {
            destroyQuietly(camera, frameCallbackRegistered);
        }
        frameCallbackRegistered = false;

        Surface surface = previewSurface;
        previewSurface = null;
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable t) {
                Log.w(TAG, "Surface.release failed", t);
            }
        }
    }

    /**
     * Silent destroy: a non-silent close posts {@code onDeviceClose} back to this listener,
     * which would schedule a second teardown and a spurious "disconnected".
     *
     * <p>Waits at most {@link #UVC_DESTROY_TIMEOUT_MS}; a camera stuck in native close is
     * abandoned so the worker can serve the next open.</p>
     */
    private static void destroyQuietly(UVCCamera camera, boolean clearFrameCallback) {
        Runnable destroy = () -> {
            if (clearFrameCallback) {
                try {
                    camera.setFrameCallback(null, 0);
                } catch (Throwable t) {
                    Log.w(TAG, "clear frame callback failed", t);
                }
            }
            try {
                camera.destroy(true);
            } catch (Throwable t) {
                Log.w(TAG, "UVCCamera.destroy failed", t);
            }
        };

        Future<?> pending;
        try {
            pending = USB_REAPER.submit(destroy);
        } catch (RejectedExecutionException e) {
            destroy.run();
            return;
        }
        try {
            pending.get(UVC_DESTROY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.e(TAG, "UVCCamera.destroy still blocked after " + UVC_DESTROY_TIMEOUT_MS
                    + "ms; abandoning it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Log.w(TAG, "UVCCamera.destroy failed", e.getCause());
        }
    }

    private static boolean runOnUsbWorker(Runnable task) {
        try {
            USB_WORKER.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "USB worker rejected task", e);
            return false;
        }
    }

    private static boolean claimDevice(String deviceName) {
        synchronized (CLAIMED_DEVICES) {
            return CLAIMED_DEVICES.add(deviceName);
        }
    }

    private static void releaseDeviceClaim(String deviceName) {
        synchronized (CLAIMED_DEVICES) {
            CLAIMED_DEVICES.remove(deviceName);
        }
    }

    private boolean isBoundDevice(UsbDevice device) {
        UsbDevice bound = boundDevice;
        return device != null && bound != null && bound.getDeviceName().equals(device.getDeviceName());
    }

    private boolean isMatchingDevice(UsbDevice device) {
        if (device == null || !isUsbCamera(device)) return false;
        return UsbCameraId.matches(getCameraId(), UsbCameraId.of(device));
    }

    /** An id that names a specific device number waits for it while it is still attached. */
    private boolean yieldsToPreferredDevice(UsbDevice device) {
        Integer preferred = UsbCameraId.preferredDeviceId(getCameraId());
        if (preferred == null || preferred == device.getDeviceId()) return false;
        UsbManager usbManager = usbManager();
        if (usbManager == null) return false;
        for (UsbDevice attached : usbManager.getDeviceList().values()) {
            if (attached.getDeviceId() == preferred) return true;
        }
        return false;
    }

    private static UsbManager usbManager() {
        Context context = ContextUtil.get();
        return context != null ? (UsbManager) context.getSystemService(Context.USB_SERVICE) : null;
    }

    private static boolean isUsbCamera(UsbDevice device) {
        final int USB_CLASS_VIDEO = 14;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUsbCameraConnected(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isUsbCamera(device)) {
                return true;
            }
        }
        return false;
    }

    private static final class Session {
        final int width;
        final int height;
        final int frameType;
        final int fps;

        Session(int width, int height, int frameType, int fps) {
            this.width = width;
            this.height = height;
            this.frameType = frameType;
            this.fps = fps;
        }
    }
}
