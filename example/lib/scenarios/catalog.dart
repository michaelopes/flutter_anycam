import 'package:flutter_anycam/flutter_anycam.dart';

import 'pages/controls_page.dart';
import 'pages/dual_camera_page.dart';
import 'pages/jpeg_page.dart';
import 'pages/lifecycle_page.dart';
import 'pages/raw_stream_page.dart';
import 'pages/rtsp_page.dart';
import 'pages/single_preview_page.dart';
import 'pages/switch_camera_page.dart';
import 'pages/tf_headless_page.dart';
import 'pages/tf_widget_page.dart';
import 'pages/triple_camera_page.dart';
import 'pages/triple_tf_page.dart';
import 'pages/usb_page.dart';
import 'pages/webrtc_feed_page.dart';
import 'scenario.dart';

List<Scenario> buildScenarioCatalog() {
  return [
    Scenario(
      id: 'single_back',
      title: 'Single back preview',
      subtitle: 'Cold bind + EventChannel frames',
      checklist: const [
        'Preview appears quickly (<1s typical)',
        'FPS counter updates',
        'No crash on open/close',
      ],
      builder: (cameras) => SinglePreviewPage(
        cameras: cameras,
        facing: FlutterAnycamLensFacing.back,
        title: 'Single back preview',
        checklist: const [
          'Preview appears quickly (<1s typical)',
          'FPS counter updates',
          'No crash on open/close',
        ],
      ),
    ),
    Scenario(
      id: 'single_front',
      title: 'Single front preview',
      subtitle: 'Front camera create/dispose',
      checklist: const [
        'Front preview works',
        'Dispose when leaving the page',
      ],
      builder: (cameras) => SinglePreviewPage(
        cameras: cameras,
        facing: FlutterAnycamLensFacing.front,
        title: 'Single front preview',
        checklist: const [
          'Front preview works',
          'Dispose when leaving the page',
        ],
      ),
    ),
    Scenario(
      id: 'dual',
      title: 'Dual camera',
      subtitle: 'Two cams by id (back+front or ids ≥100)',
      checklist: const [
        'Both previews visible (back+front OR two distinct ids)',
        'Both receive frames',
        'No StackOverflow / onFailed crash on non-concurrent devices',
      ],
      builder: (cameras) => DualCameraPage(
        cameras: cameras,
        checklist: const [
          'Both previews visible (back+front OR two distinct ids)',
          'Both receive frames',
          'No StackOverflow / onFailed crash on non-concurrent devices',
        ],
      ),
    ),
    Scenario(
      id: 'switch',
      title: 'Switch camera',
      subtitle: 'didUpdateWidget between two ids',
      checklist: const [
        'Tapping Switch changes camera id without leaving page',
        'Frames resume after switch',
      ],
      builder: (cameras) => SwitchCameraPage(
        cameras: cameras,
        checklist: const [
          'Tapping Switch changes camera id without leaving page',
          'Frames resume after switch',
        ],
      ),
    ),
    Scenario(
      id: 'controls',
      title: 'Zoom / exposure / flash',
      subtitle: 'getCameraById targeting',
      checklist: const [
        'Zoom applies to selected cameraId',
        'Exposure moves when supported',
        'Flash toggles on back camera',
      ],
      builder: (cameras) => ControlsPage(
        cameras: cameras,
        checklist: const [
          'Zoom applies to selected cameraId',
          'Exposure moves when supported',
          'Flash toggles on back camera',
        ],
      ),
    ),
    Scenario(
      id: 'jpeg',
      title: 'JPEG conversion',
      subtitle: 'MethodChannel Result on main thread',
      checklist: const [
        'Thumbnail JPEG updates',
        'lastMs stays reasonable',
        'No platform exception on convert',
      ],
      builder: (cameras) => JpegPage(
        cameras: cameras,
        checklist: const [
          'Thumbnail JPEG updates',
          'lastMs stays reasonable',
          'No platform exception on convert',
        ],
      ),
    ),
    Scenario(
      id: 'raw',
      title: 'Raw stream',
      subtitle: 'CameraStreamManager (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'Raw stream is Android-only',
      checklist: const [
        'rawFrames increments with preview open',
        'Works on CameraX and Camera2 dual fallback',
      ],
      builder: (cameras) => RawStreamPage(
        cameras: cameras,
        checklist: const [
          'rawFrames increments with preview open',
          'Works on CameraX and Camera2 dual fallback',
        ],
      ),
    ),
    Scenario(
      id: 'lifecycle',
      title: 'Lifecycle',
      subtitle: 'Unmount + Android pause/resume',
      checklist: const [
        'Unmount/remount restores preview',
        'Background app ≥200ms then resume (Android recreate)',
        'Events still arrive after resume',
      ],
      builder: (cameras) => LifecyclePage(
        cameras: cameras,
        checklist: const [
          'Unmount/remount restores preview',
          'Background app ≥200ms then resume (Android recreate)',
          'Events still arrive after resume',
        ],
      ),
    ),
    Scenario(
      id: 'tf_widget',
      title: 'TF widget',
      subtitle: 'vehicle_detection.tflite',
      checklist: const [
        'Inferences increment',
        'Crop JPEG may appear when vehicle detected',
      ],
      builder: (cameras) => TfWidgetPage(
        cameras: cameras,
        checklist: const [
          'Inferences increment',
          'Crop JPEG may appear when vehicle detected',
        ],
      ),
    ),
    Scenario(
      id: 'tf_headless',
      title: 'TF headless stream',
      subtitle: 'Camera2 without preview (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'Headless TF camera stream is Android-only',
      checklist: const [
        'status becomes connected',
        'frames increment without Texture preview',
      ],
      builder: (cameras) => TfHeadlessPage(
        cameras: cameras,
        checklist: const [
          'status becomes connected',
          'frames increment without Texture preview',
        ],
      ),
    ),
    Scenario(
      id: 'webrtc_feed',
      title: 'WebRTC camera feed',
      subtitle: 'Native attach/detach (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'Native WebRTC feed is Android-only',
      checklist: const [
        'Attach succeeds while preview is open',
        'Detach cleans up; can attach again',
        'No remote peer required for this smoke test',
      ],
      builder: (cameras) => WebRtcFeedPage(
        cameras: cameras,
        checklist: const [
          'Attach succeeds while preview is open',
          'Detach cleans up; can attach again',
          'No remote peer required for this smoke test',
        ],
      ),
    ),
    Scenario(
      id: 'usb',
      title: 'USB camera',
      subtitle: 'Manual lab: UVC Connect / FPS / analysis thumb (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'USB UVC is Android-only in this plugin',
      checklist: const [
        'Plug UVC → Refresh USB → device appears',
        'Connect → preview + analysisFps updates',
        'Raise Target FPS (30/60), note max= peak; thumb off for true max',
        'Analysis thumb colors match preview when enabled',
        'Disconnect stops session',
      ],
      builder: (cameras) => UsbPage(
        cameras: cameras,
        checklist: const [
          'Plug UVC → Refresh USB → device appears',
          'Connect → preview + analysisFps updates',
          'Raise Target FPS (30/60), note max= peak; thumb off for true max',
          'Analysis thumb colors match preview when enabled',
          'Disconnect stops session',
        ],
      ),
    ),
    Scenario(
      id: 'rtsp',
      title: 'RTSP',
      subtitle: 'Manual lab: rtsp://192.168.1.21:8554 (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'RTSP is Android-only',
      checklist: const [
        'URL prefilled with rtsp://192.168.1.21:8554 (or tap Lab chip)',
        'Connect → preview + analysisFps updates',
        'Raise Target FPS (30/60), note max= peak; thumb off for true max',
        'Analysis thumb colors match preview when enabled',
        'Disconnect stops session',
      ],
      builder: (_) => const RtspPage(
        checklist: [
          'URL prefilled with rtsp://192.168.1.21:8554 (or tap Lab chip)',
          'Connect → preview + analysisFps updates',
          'Raise Target FPS (30/60), note max= peak; thumb off for true max',
          'Analysis thumb colors match preview when enabled',
          'Disconnect stops session',
        ],
      ),
    ),
    Scenario(
      id: 'triple',
      title: 'Triple cameras',
      subtitle: 'USB + RTSP + rear preview together (Android)',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'USB and RTSP are Android-only',
      checklist: const [
        'Refresh cams until USB + rear ids are listed',
        'Lab RTSP URL prefilled (192.168.1.21:8554)',
        'Open 3 cameras → three stacked previews',
        'Each lane fps/frames increment independently',
        'Close tears down all three sessions',
      ],
      builder: (cameras) => TripleCameraPage(
        cameras: cameras,
        checklist: const [
          'Refresh cams until USB + rear ids are listed',
          'Lab RTSP URL prefilled (192.168.1.21:8554)',
          'Open 3 cameras → three stacked previews',
          'Each lane fps/frames increment independently',
          'Close tears down all three sessions',
        ],
      ),
    ),
    Scenario(
      id: 'triple_tf',
      title: 'Triple TF analysis',
      subtitle: 'vehicle_detection.tflite on USB + RTSP + rear',
      platforms: const {ScenarioPlatform.android},
      androidOnlyReason: 'USB, RTSP and native TF are Android-only',
      checklist: const [
        'Open 3 cameras with USB + lab RTSP + rear',
        'Each lane inferences increment (3 model sessions)',
        'Crop JPEG may appear per lane when a vehicle is detected',
        'Status shows inf/det/fps/ms for USB, RTSP and back',
        'Close disposes all TF sessions',
      ],
      builder: (cameras) => TripleTfPage(
        cameras: cameras,
        checklist: const [
          'Open 3 cameras with USB + lab RTSP + rear',
          'Each lane inferences increment (3 model sessions)',
          'Crop JPEG may appear per lane when a vehicle is detected',
          'Status shows inf/det/fps/ms for USB, RTSP and back',
          'Close disposes all TF sessions',
        ],
      ),
    ),
  ];
}

Scenario? scenarioById(String id) {
  for (final s in buildScenarioCatalog()) {
    if (s.id == id) return s;
  }
  return null;
}
