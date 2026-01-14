package br.dev.michaellopes.flutter_anycam.camera;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.net.Uri;
import android.os.Handler;

import android.os.Looper;


import androidx.annotation.NonNull;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.exoplayer.ExoPlayer;

import br.dev.michaellopes.flutter_anycam.model.FrameImage;
import br.dev.michaellopes.flutter_anycam.result_process.RTSPCameraProcessor;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.Log;
import io.flutter.view.TextureRegistry;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@UnstableApi
public class RTSPCamera extends BaseCamera<FrameImage> {


    private ExoPlayer player;
    private ExecutorService executorService = Executors.newFixedThreadPool(1);

    private VideoSize videoSize;

    final AtomicBoolean setuped = new AtomicBoolean(false);

    private ImageReader imageReader = ImageReader.newInstance(
            4,
            3,
            PixelFormat.RGBA_8888,
            1
    );


    FrameRateLimiterUtil<FrameImage> limiter = new FrameRateLimiterUtil<FrameImage>(getFps()) {
        @Override
        protected void onFrameLimited(FrameImage image) {
            executorService.execute(() -> {
                analyze(image);
            });
        }
    };

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params, new RTSPCameraProcessor());
    }

    @Override
    protected void init() {
        player = new ExoPlayer.Builder(ContextUtil.get()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    if (videoSize == null) {
                        videoSize = player.getVideoSize();
                        setupImageReader();
                        Log.d("RTSP", "Resolução: " + videoSize.width + "x" + videoSize.height);
                    }

                } else if (state == Player.STATE_ENDED) {
                    onDisconnected();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Player.Listener.super.onPlayerError(error);
                onFailed(error.getMessage());
            }
        });

        final MediaItem mediaItem = buildRtspMediaItem();
        player.setVideoSurface(imageReader.getSurface());
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);

    }


    private void setupImageReader() {
        synchronized (setuped) {
            if (!setuped.getAndSet(true)) {
                try {
                    player.clearVideoSurface();

                    if (imageReader != null) {
                        imageReader.close();
                        imageReader = null;
                    }

                    int width = videoSize.width;
                    int height = videoSize.height;

                    imageReader = ImageReader.newInstance(
                            width,
                            height,
                            ImageFormat.YUV_420_888,
                            30
                    );

                    ImageWriter imageWriter = ImageWriter.newInstance(getSurface(), 2);

                    imageReader.setOnImageAvailableListener(reader -> {
                        Image image = reader.acquireLatestImage();
                      //  imageWriter.queueInputImage(image);
                        if (image == null) return;
                        try {
                            Image.Plane[] planes = image.getPlanes();
                            if (planes == null || planes.length < 3) {
                                return;
                            }

                            ByteBuffer yByteBuffer = planes[0].getBuffer().duplicate();
                            ByteBuffer uByteBuffer = planes[1].getBuffer().duplicate();
                            ByteBuffer vByteBuffer = planes[2].getBuffer().duplicate();

                            yByteBuffer.rewind();
                            uByteBuffer.rewind();
                            vByteBuffer.rewind();

                            int ySize = yByteBuffer.remaining();
                            int uSize = uByteBuffer.remaining();
                            int vSize = vByteBuffer.remaining();

                            byte[] yData = new byte[ySize];
                            byte[] uData = new byte[uSize];
                            byte[] vData = new byte[vSize];

                            yByteBuffer.get(yData);
                            uByteBuffer.get(uData);
                            vByteBuffer.get(vData);

                          //  image.close();

                        } catch (Exception e) {
                            e.printStackTrace();
                          //
                        } finally {
                            image.close();
                        }
                    }, new Handler(Looper.getMainLooper()));

                    player.setVideoSurface(imageReader.getSurface());
                    ((RTSPCameraProcessor) resultProcessor).setRotationDegrees(0);
                    final Map<String, Object> result = new HashMap<>();
                    result.put("width", width);
                    result.put("height", height);
                    onConnected(result);

                } catch (Exception e) {
                    e.printStackTrace();
                    onFailed(e.getMessage());
                }
            }
        }
    }


    private MediaItem buildRtspMediaItem() {
        String url = cameraSelector.getCameraSelectorRTSP().url;
        String username = cameraSelector.getCameraSelectorRTSP().username;
        String password = cameraSelector.getCameraSelectorRTSP().password;
        Uri uri = Uri.parse(url);
        Uri authenticatedUri = uri.buildUpon()
                .encodedAuthority(
                        Uri.encode(username) + ":" +
                                Uri.encode(password) + "@" +
                                uri.getAuthority()
                )
                .build();
        return MediaItem.fromUri(authenticatedUri);
    }

    public void analyze(@NonNull FrameImage image) {
        try {
            Map<String, Object> imageData = resultProcessor.process(image, image.getWidth(), image.getHeight(), getCustomRotationDegrees());
            onVideoFrameReceived(imageData);
        } catch (Exception e) {
          e.printStackTrace();
        }
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    @Override
    public void dispose() {
        if (player != null) {
            player.stop();
            player.clearVideoSurface();
            player.clearMediaItems();
            player.release();
            player = null;
        }
        super.dispose();
    }

}