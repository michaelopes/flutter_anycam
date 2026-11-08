package br.dev.michaellopes.flutter_anycam.utils;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-decode RTSP pipeline: the decoder renders into an OES texture owned by this
 * renderer's GL thread. Each frame is drawn to Flutter's SurfaceTexture (preview) and,
 * only when the sink asks for it, rendered into an FBO and read back as libyuv ARGB
 * (bytes B,G,R,A), top-down, tightly packed.
 *
 * <p>GLES 3 contexts read back asynchronously through a PBO + fence so the GL thread keeps
 * presenting preview frames while the copy completes; GLES 2 (or any PBO failure) falls
 * back to a synchronous glReadPixels.</p>
 */
public final class RtspGlRenderer implements SurfaceTexture.OnFrameAvailableListener {

    public interface FrameSink {
        /** GL thread. Return true when this frame should be read back. Must be cheap. */
        boolean wantsFrame();

        /** GL thread. Ownership of {@code frame} moves to the sink, which must release it. */
        void onFrame(ReadbackBufferPool.Frame frame);
    }

    private static final String TAG = "RtspGlRenderer";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;
    private static final int MAX_READBACKS_IN_FLIGHT = 1;
    private static final long PBO_POLL_INTERVAL_MS = 2;
    private static final long PBO_MAX_WAIT_MS = 100;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform float uFlipY;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPosition.x, aPosition.y * uFlipY, 0.0, 1.0);\n" +
            "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uSwapRB;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTexture, vTexCoord).rgb;\n" +
            "  gl_FragColor = vec4(mix(c, c.bgr, uSwapRB), 1.0);\n" +
            "}\n";

    private static final float[] QUAD_POSITIONS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] QUAD_TEXCOORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final HandlerThread thread;
    private final Handler handler;
    @Nullable
    private final SurfaceTexture previewTexture;
    private final FrameSink sink;
    private final ReadbackBufferPool pool = new ReadbackBufferPool(MAX_READBACKS_IN_FLIGHT);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final float[] texMatrix = new float[16];
    private final Runnable pollReadback = () -> {
        try {
            pollPendingReadback();
        } catch (Throwable t) {
            Log.w(TAG, "Readback poll failed", t);
        }
    };

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbufferSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
    private Surface windowTarget;
    private int windowWidth;
    private int windowHeight;
    private boolean previewDetached = false;
    private boolean previewBroken = false;
    private int glesVersion;

    private int oesTexture;
    private SurfaceTexture decoderTexture;
    private Surface decoderSurface;

    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uTexMatrix;
    private int uFlipY;
    private int uSwapRB;
    private int uTexture;
    private FloatBuffer positions;
    private FloatBuffer texCoords;

    private int fbo;
    private int fboTexture;
    private int fboWidth;
    private int fboHeight;

    private boolean usePbo;
    private int pbo;
    private int pboSize;
    private long pendingFence;
    private ReadbackBufferPool.Frame pendingFrame;
    private long pendingSinceMs;

    private volatile int videoWidth;
    private volatile int videoHeight;

    /**
     * Blocks until the GL thread is ready. Returns null when EGL/GLES cannot be set up,
     * in which case the caller should use the non-GL pipeline.
     */
    @Nullable
    public static RtspGlRenderer create(@Nullable SurfaceTexture previewTexture, FrameSink sink, long timeoutMs) {
        RtspGlRenderer renderer = new RtspGlRenderer(previewTexture, sink);
        final boolean[] ok = {false};
        boolean completed = renderer.runSync(() -> {
            try {
                renderer.initGl();
                ok[0] = true;
            } catch (Throwable t) {
                Log.w(TAG, "GL init failed", t);
                renderer.releaseGl();
            }
        }, timeoutMs);
        if (!completed || !ok[0]) {
            renderer.released.set(true);
            renderer.handler.post(renderer::releaseGl);
            renderer.thread.quitSafely();
            return null;
        }
        return renderer;
    }

    private RtspGlRenderer(@Nullable SurfaceTexture previewTexture, FrameSink sink) {
        this.previewTexture = previewTexture;
        this.sink = sink;
        this.thread = new HandlerThread("RtspGlRenderer");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** Producer surface for the video decoder. */
    public Surface getDecoderSurface() {
        return decoderSurface;
    }

    /** Any thread. Size of the decoded (cropped) picture. */
    public void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        videoWidth = width;
        videoHeight = height;
    }

    /**
     * Stops drawing into Flutter's SurfaceTexture and disconnects from it, so the texture
     * can be released safely afterwards. Decoding and readback keep working.
     */
    public void detachPreview(long timeoutMs) {
        runSync(() -> {
            previewDetached = true;
            destroyWindowSurface();
        }, timeoutMs);
    }

    /** Releases every GL/EGL resource and stops the GL thread. Idempotent. */
    public void release(long timeoutMs) {
        if (!released.compareAndSet(false, true)) return;
        runSync(this::releaseGl, timeoutMs);
        thread.quitSafely();
    }

    // ========= GL thread =========

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (released.get() || decoderTexture == null) return;
        // An exception escaping a Handler callback would kill the process.
        try {
            processFrame();
        } catch (Throwable t) {
            Log.w(TAG, "Frame processing failed", t);
        }
    }

    private void processFrame() {
        makeCurrent(windowSurface != EGL14.EGL_NO_SURFACE ? windowSurface : pbufferSurface);
        decoderTexture.updateTexImage();
        decoderTexture.getTransformMatrix(texMatrix);

        pollPendingReadback();

        int width = videoWidth;
        int height = videoHeight;
        if (width <= 0 || height <= 0) return;

        if (previewTexture != null && !previewDetached && !previewBroken) {
            drawPreview(width, height);
        }

        if (pendingFrame == null && sink.wantsFrame()) {
            readback(width & ~1, height & ~1, decoderTexture.getTimestamp());
        }
    }

    private void initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY;
            throw new IllegalStateException("eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        if (!createContext(3) && !createContext(2)) {
            throw new IllegalStateException("eglCreateContext failed");
        }

        int[] pbufferAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbufferSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0);
        if (pbufferSurface == null || pbufferSurface == EGL14.EGL_NO_SURFACE) {
            pbufferSurface = EGL14.EGL_NO_SURFACE;
            throw new IllegalStateException("eglCreatePbufferSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        makeCurrent(pbufferSurface);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTexture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        buildProgram();
        positions = floatBuffer(QUAD_POSITIONS);
        texCoords = floatBuffer(QUAD_TEXCOORDS);
        checkGl("init");

        decoderTexture = new SurfaceTexture(oesTexture);
        decoderTexture.setOnFrameAvailableListener(this, handler);
        decoderSurface = new Surface(decoderTexture);

        usePbo = glesVersion >= 3;
        Log.i(TAG, "GL ready: GLES " + glesVersion + ", " + GLES20.glGetString(GLES20.GL_RENDERER)
                + (usePbo ? ", async readback" : ", sync readback"));
    }

    private boolean createContext(int clientVersion) {
        int renderable = clientVersion >= 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL14.EGL_OPENGL_ES2_BIT;
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            return false;
        }
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE};
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        config = configs[0];
        context = ctx;
        glesVersion = clientVersion;
        return true;
    }

    private void buildProgram() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("Program link failed: " + log);
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uFlipY = GLES20.glGetUniformLocation(program, "uFlipY");
        uSwapRB = GLES20.glGetUniformLocation(program, "uSwapRB");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }

    private void draw(boolean readbackPass) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        // Readback: flip so glReadPixels (bottom-up) yields top-down rows, and swap R/B so the
        // bytes land as B,G,R,A (libyuv ARGB).
        GLES20.glUniform1f(uFlipY, readbackPass ? -1f : 1f);
        GLES20.glUniform1f(uSwapRB, readbackPass ? 1f : 0f);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    // ----- preview -----

    private void drawPreview(int width, int height) {
        if (!ensureWindowSurface(width, height)) return;
        makeCurrent(windowSurface);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        draw(false);
        if (!EGL14.eglSwapBuffers(display, windowSurface)) {
            int error = EGL14.eglGetError();
            Log.w(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(error));
            if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
                destroyWindowSurface();
                previewBroken = true;
            }
        }
    }

    private boolean ensureWindowSurface(int width, int height) {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            if (windowWidth == width && windowHeight == height) return true;
            destroyWindowSurface();
        }
        try {
            previewTexture.setDefaultBufferSize(width, height);
            windowTarget = new Surface(previewTexture);
            EGLSurface surface = EGL14.eglCreateWindowSurface(display, config, windowTarget, new int[]{EGL14.EGL_NONE}, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            }
            windowSurface = surface;
            windowWidth = width;
            windowHeight = height;
            makeCurrent(windowSurface);
            // Never block the GL thread on Flutter's consumer; newer frames replace older ones.
            EGL14.eglSwapInterval(display, 0);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Preview surface unavailable", t);
            destroyWindowSurface();
            previewBroken = true;
            return false;
        }
    }

    private void destroyWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbufferSurface);
            EGL14.eglDestroySurface(display, windowSurface);
            windowSurface = EGL14.EGL_NO_SURFACE;
        }
        if (windowTarget != null) {
            windowTarget.release();
            windowTarget = null;
        }
        windowWidth = 0;
        windowHeight = 0;
    }

    // ----- readback -----

    private void readback(int width, int height, long timestampNs) {
        if (width < 2 || height < 2) return;
        ReadbackBufferPool.Frame frame = pool.acquire(width, height);
        if (frame == null) return;
        frame.timestampNs = timestampNs;

        try {
            ensureFbo(width, height);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, width, height);
            draw(true);

            if (usePbo && startAsyncReadback(frame)) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return;
            }

            frame.buffer.clear();
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.buffer);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            checkGl("glReadPixels");
            frame.buffer.rewind();
            deliver(frame);
        } catch (Throwable t) {
            Log.w(TAG, "Readback failed", t);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            frame.release();
        }
    }

    private boolean startAsyncReadback(ReadbackBufferPool.Frame frame) {
        int size = frame.width * frame.height * 4;
        try {
            ensurePbo(size);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo);
            GLES30.glReadPixels(0, 0, frame.width, frame.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GLES20.glFlush();
            checkGl("async readback");
            if (fence == 0) {
                throw new IllegalStateException("glFenceSync returned 0");
            }
            pendingFence = fence;
            pendingFrame = frame;
            pendingSinceMs = SystemClock.uptimeMillis();
            handler.postDelayed(pollReadback, PBO_POLL_INTERVAL_MS);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "PBO readback unavailable, using synchronous readback", t);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            disablePbo();
            return false;
        }
    }

    private void pollPendingReadback() {
        if (pendingFrame == null || released.get()) return;
        handler.removeCallbacks(pollReadback);

        boolean overdue = SystemClock.uptimeMillis() - pendingSinceMs >= PBO_MAX_WAIT_MS;
        long timeoutNs = overdue ? TimeUnit.MILLISECONDS.toNanos(PBO_MAX_WAIT_MS) : 0;
        int status = GLES30.glClientWaitSync(pendingFence, 0, timeoutNs);
        if (status == GLES30.GL_TIMEOUT_EXPIRED && !overdue) {
            handler.postDelayed(pollReadback, PBO_POLL_INTERVAL_MS);
            return;
        }

        ReadbackBufferPool.Frame frame = pendingFrame;
        pendingFrame = null;
        GLES30.glDeleteSync(pendingFence);
        pendingFence = 0;

        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
            Log.w(TAG, "Readback fence status 0x" + Integer.toHexString(status) + ", dropping frame");
            frame.release();
            if (status == GLES30.GL_WAIT_FAILED) disablePbo();
            return;
        }

        int size = frame.width * frame.height * 4;
        boolean copied = false;
        try {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo);
            ByteBuffer mapped = (ByteBuffer) GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT);
            if (mapped != null) {
                frame.buffer.clear();
                frame.buffer.put(mapped);
                frame.buffer.rewind();
                copied = true;
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        } catch (Throwable t) {
            Log.w(TAG, "PBO map failed", t);
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        }

        if (!copied) {
            frame.release();
            disablePbo();
            return;
        }
        deliver(frame);
    }

    private void deliver(ReadbackBufferPool.Frame frame) {
        if (released.get()) {
            frame.release();
            return;
        }
        try {
            sink.onFrame(frame);
        } catch (Throwable t) {
            Log.w(TAG, "Sink failed", t);
            frame.release();
        }
    }

    private void ensureFbo(int width, int height) {
        if (fbo != 0 && fboWidth == width && fboHeight == height) return;
        deleteFbo();

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        fboTexture = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            deleteFbo();
            throw new IllegalStateException("FBO incomplete: 0x" + Integer.toHexString(status));
        }
        fboWidth = width;
        fboHeight = height;
    }

    private void deleteFbo() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            fbo = 0;
        }
        if (fboTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0);
            fboTexture = 0;
        }
        fboWidth = 0;
        fboHeight = 0;
    }

    private void ensurePbo(int size) {
        if (pbo != 0 && pboSize == size) return;
        deletePbo();
        int[] ids = new int[1];
        GLES30.glGenBuffers(1, ids, 0);
        pbo = ids[0];
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, size, null, GLES30.GL_STREAM_READ);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        pboSize = size;
    }

    private void deletePbo() {
        if (pbo != 0) {
            GLES30.glDeleteBuffers(1, new int[]{pbo}, 0);
            pbo = 0;
        }
        pboSize = 0;
    }

    private void disablePbo() {
        usePbo = false;
        deletePbo();
    }

    // ----- lifecycle -----

    private void releaseGl() {
        handler.removeCallbacks(pollReadback);
        if (decoderTexture != null) {
            decoderTexture.setOnFrameAvailableListener(null);
        }
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT && pbufferSurface != EGL14.EGL_NO_SURFACE) {
            try {
                makeCurrent(pbufferSurface);
                if (pendingFence != 0) {
                    GLES30.glDeleteSync(pendingFence);
                }
                deletePbo();
                deleteFbo();
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
                if (oesTexture != 0) {
                    GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
                    oesTexture = 0;
                }
            } catch (Throwable t) {
                Log.w(TAG, "GL cleanup failed", t);
            }
        }
        pendingFence = 0;
        if (pendingFrame != null) {
            pendingFrame.release();
            pendingFrame = null;
        }
        if (decoderTexture != null) {
            decoderTexture.release();
            decoderTexture = null;
        }
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, windowSurface);
                windowSurface = EGL14.EGL_NO_SURFACE;
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbufferSurface);
                pbufferSurface = EGL14.EGL_NO_SURFACE;
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
                context = EGL14.EGL_NO_CONTEXT;
            }
            // No eglTerminate: the default display is process-wide (Flutter, WebRTC).
            EGL14.eglReleaseThread();
            display = EGL14.EGL_NO_DISPLAY;
        }
        if (windowTarget != null) {
            windowTarget.release();
            windowTarget = null;
        }
    }

    // ----- helpers -----

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private static void checkGl(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(op + ": glError 0x" + Integer.toHexString(error));
        }
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    /** Runs on the GL thread and waits; returns false on timeout. */
    private boolean runSync(Runnable task, long timeoutMs) {
        if (Looper.myLooper() == handler.getLooper()) {
            task.run();
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        if (!posted) return false;
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
