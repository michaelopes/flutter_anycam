package br.dev.michaellopes.flutter_anycam.camera;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import br.dev.michaellopes.flutter_anycam.result_process.RTSPCameraProcessor;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.Log;
import io.flutter.view.TextureRegistry;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


public class RTSPCamera extends BaseCamera<Image> {

   final AtomicBoolean isProcessing = new AtomicBoolean(false);
   final Object lock = new Object();

    private ExoPlayer player;
    private ImageReader imageReader = ImageReader.newInstance(
            4,
            3,
            ImageFormat.YUV_420_888,
            1
    );

    private VideoSize videoSize;

    FrameRateLimiterUtil<Image> limiter = new FrameRateLimiterUtil<Image>(getFps()) {
        @Override
        protected void onFrameLimited(Image image) {
            analyze(image);
        }
    };

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params, new RTSPCameraProcessor());
    }

    @Override
    protected void init() {

        player = new ExoPlayer.Builder(ContextUtil.get()).build();
        player.addListener(new Player.Listener() {

            @Override
            public void onPlaybackStateChanged(int state) {
                if(state == Player.STATE_READY ) {
                    if(videoSize == null) {
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
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
        player.setVideoSurface(imageReader.getSurface());
    }


    private void setupImageReader() {

        if (imageReader != null) {
            player.clearVideoSurface();
            imageReader.close();
            imageReader = null;
        }

        int width = videoSize.width;
        int height = videoSize.height;

        imageReader = ImageReader.newInstance(
                width,
                height,
                ImageFormat.YUV_420_888,
                1
        );

        ImageWriter imageWriter = ImageWriter.newInstance(getSurface(), 2);


        player.setVideoSurface(imageReader.getSurface());

        imageReader.setOnImageAvailableListener(reader -> {
            synchronized(lock) {
                if (isProcessing.get()) return;

                isProcessing.set(true);
                // NÃO use try-with-resources aqui
                Image image = reader.acquireLatestImage();
                if (image == null) return;

                if (image.getFormat() != ImageFormat.YUV_420_888) {
                    // Enfileirar de volta imediatamente sem processar
                    if (imageWriter != null) {
                        imageWriter.queueInputImage(image);
                    } else {
                        image.close();
                    }
                    isProcessing.set(false);
                    return;
                }

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

                    byte[] yData = new byte[width * height];
                    byte[] uData = new byte[uSize];
                    byte[] vData = new byte[vSize];

                    int yCapacity = yByteBuffer.capacity();
                    int yRemaining = yByteBuffer.remaining();
                    System.out.println("DEBUG yCapacity: "  + yCapacity + " yRemaining: " + yRemaining);
                    Log.d("DEBUG", "yData.length: " + yData.length);
                    Log.d("DEBUG", "Image width: " + image.getWidth() + ", height: " + image.getHeight());

                    // COPIAR IMEDIATAMENTE - sincronamente
                    yByteBuffer.get(yData);
                    uByteBuffer.get(uData);
                    vByteBuffer.get(vData);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    isProcessing.set(false);
                    image.close();
                }
            }
        }, new Handler(Looper.getMainLooper()));


        ((RTSPCameraProcessor) resultProcessor).setRotationDegrees(videoSize.unappliedRotationDegrees);

        final Map<String, Object> result = new HashMap<>();
        result.put("width", width);
        result.put("height", height);
        onConnected(result);

//            if(wait.get()) {
//                Image img = reader.acquireLatestImage();
//                if (img != null) img.close();
//                return;
//            }
//
//            wait.set(true);
//            Image image = reader.acquireLatestImage();
//            if (image == null) return;
//            limiter.onNewFrame(image);
//            //imageWriter.queueInputImage(image);
//         //   image.close();
//            wait.set(false);
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

    public void analyze(@NonNull Image image) {
        try {
            Map<String, Object> imageData = resultProcessor.process(image, image.getWidth(), image.getHeight(), getCustomRotationDegrees());
        //    onVideoFrameReceived(imageData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //image.close();
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