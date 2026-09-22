package br.dev.michaellopes.flutter_anycam.camera;

import android.media.Image;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alexvas.rtsp.codec.VideoDecodeThread;
import com.alexvas.rtsp.codec.VideoDecoderSurfaceThread;
import com.alexvas.rtsp.codec.VideoFrameQueue;
import com.alexvas.rtsp.widget.RtspDataListener;
import com.alexvas.rtsp.widget.RtspProcessor;
import com.alexvas.rtsp.widget.RtspStatusListener;
import com.limelight.binding.video.MediaCodecHelper;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.ReadbackBufferPool;
import br.dev.michaellopes.flutter_anycam.utils.RtspDecoderUtil;
import br.dev.michaellopes.flutter_anycam.utils.RtspGlRenderer;
import io.flutter.view.TextureRegistry;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.Nv21Buffer;

/**
 * RTSP camera with the same widget contract as {@link DeviceCamera}.
 *
 * <p>Transport, depacketizing and the (low-latency) hardware decoder come from
 * {@link RtspProcessor}. The decoder output goes to one of two pipelines:</p>
 * <ul>
 *   <li><b>GL</b> (default): a single decode into {@link RtspGlRenderer}'s OES texture, drawn to
 *   Flutter's texture for preview and read back on demand for widget frames / TF / raw and
 *   WebRTC sinks. Sinks registered after start are picked up per frame.</li>
 *   <li><b>Fallback</b> (EGL unavailable): the library decodes straight into Flutter's surface,
 *   and a second {@link RtspDecoderUtil} decoder produces {@link Image}s for analysis, only
 *   when frame delivery or a sink is active at connect time.</li>
 * </ul>
 */
public class RTSPCamera extends BaseCamera {

    private static final String TAG = "RTSPCamera";
    private static final String USER_AGENT = "flutter_anycam";
    private static final int RTSPS_DEFAULT_PORT = 322;
    private static final int DECODER_HINT_WIDTH = 1920;
    private static final int DECODER_HINT_HEIGHT = 1080;
    private static final long GL_INIT_TIMEOUT_MS = 1500;
    private static final long PREVIEW_DETACH_TIMEOUT_MS = 500;
    private static final long DECODER_JOIN_TIMEOUT_MS = 1500;
    private static final long GL_RELEASE_TIMEOUT_MS = 1000;

    private static final ExecutorService TEARDOWN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RtspTeardown");
        t.setDaemon(true);
        return t;
    });

    private final boolean previewEnabled;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final RtspEventGate events = new RtspEventGate();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "RtspAnalysis"));

    /** FPS gate for widget frame delivery. Raw/WebRTC sinks have their own limiters. */
    private final FrameRateLimiterUtil<Object> deliveryLimiter =
            new FrameRateLimiterUtil<Object>(getFps()) {
                @Override
                protected void onFrameLimited(Object ignored) {
                }
            };

    private RtspProcessor processor;
    @Nullable
    private volatile RtspGlRenderer renderer;
    @Nullable
    private Surface fallbackPreviewSurface;
    @Nullable
    private volatile RtspDecoderUtil fallbackAnalysis;
    @Nullable
    private volatile VideoDecodeThread decodeThread;

    /** Analysis-thread only. */
    private ByteBuffer nv21Scratch;

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
        boolean preview = true;
        if (params.get("previewEnabled") != null) {
            preview = (Boolean) params.get("previewEnabled");
        }
        this.previewEnabled = preview;
    }

    @Override
    protected void init() {
        if (disposed.get() || !started.compareAndSet(false, true)) {
            return;
        }

        try {
            MediaCodecHelper.initialize(ContextUtil.get(), "");
        } catch (Throwable t) {
            Log.w(TAG, "MediaCodecHelper init failed; decoder quirks table unavailable", t);
        }

        renderer = RtspGlRenderer.create(
                previewEnabled ? texture.surfaceTexture() : null,
                glSink,
                GL_INIT_TIMEOUT_MS
        );
        if (renderer == null) {
            Log.w(TAG, "GL pipeline unavailable, using direct-surface fallback");
        }

        CameraSelectorRtspArgs args = CameraSelectorRtspArgs.from(cameraSelector);
        processor = new RtspProcessor(this::createVideoDecoder);
        processor.setDebug(false);
        processor.setStatusListener(statusListener);
        if (renderer == null) {
            processor.setDataListener(fallbackDataListener);
        }
        processor.init(args.uri, args.username, args.password, USER_AGENT, RtspProcessor.DEFAULT_SOCKET_TIMEOUT);
        processor.start(true, false, false);
    }

    /** Invoked by RtspProcessor on its RTSP thread once the SDP is known. */
    private VideoDecodeThread createVideoDecoder(
            String mimeType,
            Integer rotation,
            VideoFrameQueue queue,
            VideoDecodeThread.VideoDecoderListener listener,
            VideoDecodeThread.DecoderType decoderType,
            Boolean frameRateStabilization
    ) {
        Surface target;
        RtspGlRenderer gl = renderer;
        if (gl != null) {
            target = gl.getDecoderSurface();
        } else {
            synchronized (this) {
                if (fallbackPreviewSurface == null) {
                    fallbackPreviewSurface = new Surface(texture.surfaceTexture());
                }
                target = fallbackPreviewSurface;
            }
            startFallbackAnalysis(mimeType);
        }

        VideoDecodeThread thread = new VideoDecoderSurfaceThread(
                target,
                mimeType,
                DECODER_HINT_WIDTH,
                DECODER_HINT_HEIGHT,
                rotation,
                queue,
                listener,
                decoderType,
                frameRateStabilization
        );
        decodeThread = thread;
        return thread;
    }

    // ========= status =========

    private final RtspStatusListener statusListener = new RtspStatusListener() {
        @Override
        public void onRtspFrameSizeChanged(int width, int height) {
            if (width <= 0 || height <= 0) return;
            RtspGlRenderer gl = renderer;
            if (gl != null) {
                gl.setVideoSize(width, height);
            } else if (!disposed.get()) {
                texture.surfaceTexture().setDefaultBufferSize(width, height);
            }
            if (events.onConnected()) {
                Map<String, Object> result = new HashMap<>();
                result.put("width", width);
                result.put("height", height);
                onConnected(result);
            }
        }

        @Override
        public void onRtspStatusFailedUnauthorized() {
            if (events.onTerminal()) {
                onUnauthorized();
            }
        }

        @Override
        public void onRtspStatusFailed(@Nullable String message) {
            if (events.onTerminal()) {
                onFailed(message);
            }
        }

        @Override
        public void onRtspStatusDisconnected() {
            if (events.onTerminal()) {
                onDisconnected();
            }
        }
    };

    // ========= GL pipeline =========

    private final RtspGlRenderer.FrameSink glSink = new RtspGlRenderer.FrameSink() {
        @Override
        public boolean wantsFrame() {
            if (disposed.get()) return false;
            return (frameDeliveryEnabled && deliveryLimiter.isFrameDue())
                    || CameraStreamManager.getInstance().isFrameDue(getCameraId());
        }

        @Override
        public void onFrame(ReadbackBufferPool.Frame frame) {
            try {
                analysisExecutor.execute(() -> processReadback(frame));
            } catch (RejectedExecutionException e) {
                frame.release();
            }
        }
    };

    /** Analysis thread. {@code frame} holds libyuv ARGB, top-down, tightly packed. */
    private void processReadback(ReadbackBufferPool.Frame frame) {
        try {
            if (disposed.get()) return;

            final int width = frame.width;
            final int height = frame.height;
            final Integer customRotation = getCustomRotationDegrees();
            final String cameraId = getCameraId();
            final CameraStreamManager streams = CameraStreamManager.getInstance();

            boolean deliver = frameDeliveryEnabled && deliveryLimiter.shouldProcessFrame();
            boolean streamDue = streams.isFrameDue(cameraId);
            boolean standard = isStandard();

            ByteBuffer nv21 = null;
            if (streamDue || (deliver && standard)) {
                nv21 = argbToNv21(frame.buffer, width, height);
            }

            if (streamDue) {
                streams.sendNv21Frame(cameraId, nv21, width, height, 0, customRotation);
            }

            if (!deliver) return;

            Map<String, Object> frameMap;
            if (standard) {
                nv21.rewind();
                frameMap = imageAnalysisUtil.usbFrameToNV21Map(nv21, width, height, resizeFrame, filter, customRotation);
            } else {
                frameMap = TfFrameHandler.getInstance().addFrameArgb(frame.buffer, width, height, filter, customRotation);
            }

            if (frameMap != null && !disposed.get()) {
                onVideoFrameReceived(frameMap);
            }
        } catch (Throwable t) {
            Log.e(TAG, "processReadback failed", t);
        } finally {
            frame.release();
        }
    }

    private ByteBuffer argbToNv21(ByteBuffer argb, int width, int height) {
        int size = width * height * 3 / 2;
        if (nv21Scratch == null || nv21Scratch.capacity() != size) {
            nv21Scratch = ByteBuffer.allocateDirect(size);
        }
        nv21Scratch.clear();

        ByteBuffer src = argb.duplicate();
        src.rewind();
        ArgbBuffer argbBuffer = ArgbBuffer.Factory.wrap(src, width, height);
        Nv21Buffer nv21Buffer = Nv21Buffer.Factory.wrap(nv21Scratch, width, height);
        try {
            argbBuffer.convertTo(nv21Buffer);
        } finally {
            argbBuffer.close();
            nv21Buffer.close();
        }
        nv21Scratch.clear();
        return nv21Scratch;
    }

    // ========= fallback pipeline =========

    private final RtspDataListener fallbackDataListener = new RtspDataListener() {
        @Override
        public void onRtspDataVideoNalUnitReceived(@NonNull byte[] data, int offset, int length, long timestamp) {
            RtspDecoderUtil decoder = fallbackAnalysis;
            if (decoder != null && !disposed.get()) {
                decoder.dispatchNal(data, offset, length, timestamp);
            }
        }
    };

    private void startFallbackAnalysis(String mimeType) {
        boolean wanted = frameDeliveryEnabled || CameraStreamManager.getInstance().hasActiveSink(getCameraId());
        if (!wanted || disposed.get()) return;

        RtspDecoderUtil previous = fallbackAnalysis;
        if (previous != null) previous.dispose();

        RtspDecoderUtil decoder = new RtspDecoderUtil(
                mimeType,
                this::analyzeImage,
                e -> Log.e(TAG, "Fallback analysis decoder failed", e)
        );
        decoder.start();
        fallbackAnalysis = decoder;
        if (disposed.get()) {
            decoder.dispose();
        }
    }

    /** Fallback analysis path; same responsibilities as DeviceCamera's Camera2 analyzer. */
    private void analyzeImage(Image image) {
        if (disposed.get() || image == null) return;
        try {
            Integer customRotation = getCustomRotationDegrees();
            CameraStreamManager.getInstance().sendFrame(getCameraId(), image, 0, customRotation);

            if (!frameDeliveryEnabled || !deliveryLimiter.shouldProcessFrame()) return;

            Map<String, Object> frameMap = isStandard()
                    ? imageAnalysisUtil.imageToNV21Map(image, 0, resizeFrame, filter, customRotation)
                    : TfFrameHandler.getInstance().addFrame(image, resizeFrame, filter, customRotation);

            if (frameMap != null && !disposed.get()) {
                onVideoFrameReceived(frameMap);
            }
        } catch (Exception e) {
            Log.e(TAG, "analyzeImage failed", e);
        }
    }

    // ========= lifecycle =========

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    /**
     * Returns immediately. Teardown order matters and runs off the main thread: stop the
     * decoder, then GL, and only then release Flutter's texture (main thread).
     */
    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) return;
        events.dispose();

        final RtspProcessor p = processor;
        if (p != null) {
            p.setStatusListener(null);
            p.setDataListener(null);
            try {
                p.stop();
                p.stopDecoders();
            } catch (Throwable t) {
                Log.w(TAG, "RtspProcessor stop failed", t);
            }
        }

        final RtspGlRenderer gl = renderer;
        if (gl != null) {
            gl.detachPreview(PREVIEW_DETACH_TIMEOUT_MS);
        }

        TEARDOWN.execute(() -> {
            VideoDecodeThread thread = decodeThread;
            if (thread != null) {
                try {
                    thread.join(DECODER_JOIN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            RtspDecoderUtil analysis = fallbackAnalysis;
            if (analysis != null) {
                analysis.dispose();
            }
            if (gl != null) {
                gl.release(GL_RELEASE_TIMEOUT_MS);
            }
            analysisExecutor.shutdownNow();
            synchronized (RTSPCamera.this) {
                if (fallbackPreviewSurface != null) {
                    fallbackPreviewSurface.release();
                    fallbackPreviewSurface = null;
                }
            }
            mainHandler.post(this::releaseBase);
        });
    }

    private void releaseBase() {
        try {
            super.dispose();
        } catch (Throwable t) {
            Log.w(TAG, "Texture release failed", t);
        }
    }

    private static final class CameraSelectorRtspArgs {
        final Uri uri;
        final String username;
        final String password;

        private CameraSelectorRtspArgs(Uri uri, String username, String password) {
            this.uri = uri;
            this.username = username;
            this.password = password;
        }

        static CameraSelectorRtspArgs from(br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector selector) {
            Uri uri = Uri.parse(selector.getCameraSelectorRTSP().url);
            if (uri.getPort() < 0 && "rtsps".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                String userInfo = uri.getEncodedUserInfo();
                String authority = (userInfo != null ? userInfo + "@" : "") + uri.getHost() + ":" + RTSPS_DEFAULT_PORT;
                uri = uri.buildUpon().encodedAuthority(authority).build();
            }
            return new CameraSelectorRtspArgs(
                    uri,
                    selector.getCameraSelectorRTSP().username,
                    selector.getCameraSelectorRTSP().password
            );
        }
    }
}
