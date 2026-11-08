import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';

FlutterAnycamTfInferenceOutputBox box(int x, int y, [int size = 100]) =>
    FlutterAnycamTfInferenceOutputBox(
      xMin: x,
      yMin: y,
      xMax: x + size,
      yMax: y + size,
      srcWidth: 1000,
      srcHeight: 1000,
    );

void main() {
  group('FlutterAnycamTfBoundingBox geometry', () {
    test('iou of identical, disjoint and half-overlapping boxes', () {
      expect(box(0, 0).iou(box(0, 0)), closeTo(1, 1e-9));
      expect(box(0, 0).iou(box(500, 500)), 0);
      expect(box(0, 0).iou(box(50, 0)), closeTo(50 / 150, 1e-9));
    });

    test('zero-sized boxes never divide by zero', () {
      final empty = box(0, 0, 0);
      expect(empty.iou(empty), 0);
      expect(empty.centerScore(empty), 0);
    });

    test('centerScore decays with distance', () {
      expect(box(0, 0).centerScore(box(0, 0)), closeTo(1, 1e-6));
      expect(box(0, 0).centerScore(box(50, 0)), closeTo(0.75, 1e-6));
      expect(box(0, 0).centerScore(box(300, 0)), 0);
    });
  });

  group('FlutterAnycamTfBBoxTracker', () {
    test('every new box gets a unique tracking id', () {
      final tracker = FlutterAnycamTfBBoxTracker();
      final boxes = [for (var i = 0; i < 50; i++) box(i * 200, 0)];

      tracker.update(boxes);

      final ids = boxes.map((b) => b.trackingId).toSet();
      expect(ids, hasLength(50));
      expect(ids.every((id) => id != null && id.length == 36), isTrue);
      expect(tracker.count, 50);
    });

    test('a box that moves a little keeps its id', () {
      final tracker = FlutterAnycamTfBBoxTracker();
      final first = box(100, 100);
      tracker.update([first]);

      String? id = first.trackingId;
      for (var step = 1; step <= 20; step++) {
        final next = box(100 + step * 5, 100);
        tracker.update([next]);
        expect(next.trackingId, id, reason: 'step $step');
      }
      expect(tracker.count, 1);
    });

    test('ids follow positions regardless of list order', () {
      final tracker = FlutterAnycamTfBBoxTracker();
      final left = box(0, 0);
      final right = box(600, 0);
      tracker.update([left, right]);

      final right2 = box(605, 0);
      final left2 = box(5, 0);
      tracker.update([right2, left2]);

      expect(left2.trackingId, left.trackingId);
      expect(right2.trackingId, right.trackingId);
    });

    test('global greedy matching: a closer box wins the track', () {
      final tracker = FlutterAnycamTfBBoxTracker();
      final original = box(100, 100);
      tracker.update([original]);

      final far = box(160, 100);
      final near = box(102, 100);
      tracker.update([far, near]);

      expect(near.trackingId, original.trackingId);
      expect(far.trackingId, isNot(original.trackingId));
    });

    test('a track survives maxMissed empty frames and is then dropped', () {
      final tracker = FlutterAnycamTfBBoxTracker(maxMissed: 3);
      final first = box(0, 0);
      tracker.update([first]);

      for (var i = 0; i < 3; i++) {
        tracker.update([]);
      }
      expect(tracker.count, 1);
      final back = box(0, 0);
      tracker.update([back]);
      expect(back.trackingId, first.trackingId);

      for (var i = 0; i < 4; i++) {
        tracker.update([]);
      }
      expect(tracker.count, 0);
      final later = box(0, 0);
      tracker.update([later]);
      expect(later.trackingId, isNot(first.trackingId));
    });

    test('tracks stay bounded under churn (no leak)', () {
      final tracker = FlutterAnycamTfBBoxTracker(maxMissed: 2);
      for (var frame = 0; frame < 500; frame++) {
        tracker.update([box((frame * 997) % 9000, (frame * 31) % 9000)]);
        expect(tracker.count, lessThanOrEqualTo(4));
      }
    });

    test('frame counter and reset', () {
      final tracker = FlutterAnycamTfBBoxTracker();
      tracker.update([box(0, 0)]);
      tracker.update([box(0, 0)], frameNumber: 42);
      expect(tracker.currentFrame, 42);

      tracker.reset();
      expect(tracker.count, 0);
      expect(tracker.currentFrame, 0);
    });
  });
}
