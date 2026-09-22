import 'flutter_anycam_tf_bbox_tracker.dart';

class FlutterAnycamTfTrackerOptions {
  final double threshold;
  final int maxMissed;
  final int minHits;
  final MatchFn matchFn;

  FlutterAnycamTfTrackerOptions({
    this.threshold = 0.3,
    this.maxMissed = 5,
    this.minHits = 3,
    MatchFn? matchFn,
  }) : matchFn = matchFn ?? MatchStrategy.iou;
}
