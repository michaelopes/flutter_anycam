/// Illumination metrics for a TF frame region (0..255 mean luminance).
class FlutterAnycamTfIlluminationScore {
  const FlutterAnycamTfIlluminationScore({
    required this.mean,
    required this.darkPixelRatio,
    required this.brightPixelRatio,
  });

  /// Average luminance in the sampled region (0 = black, 255 = white).
  final double mean;

  /// Fraction of sampled pixels below the native dark threshold.
  final double darkPixelRatio;

  /// Fraction of sampled pixels above the native bright threshold.
  final double brightPixelRatio;

  bool isTooDark({
    double meanThreshold = 55,
    double darkRatioThreshold = 0.35,
  }) {
    return mean < meanThreshold || darkPixelRatio > darkRatioThreshold;
  }

  bool isTooBright({
    double meanThreshold = 200,
    double brightRatioThreshold = 0.25,
  }) {
    return mean > meanThreshold || brightPixelRatio > brightRatioThreshold;
  }

  bool isBalanced({
    double darkMeanThreshold = 55,
    double brightMeanThreshold = 200,
    double darkRatioThreshold = 0.35,
    double brightRatioThreshold = 0.25,
  }) {
    return !isTooDark(
          meanThreshold: darkMeanThreshold,
          darkRatioThreshold: darkRatioThreshold,
        ) &&
        !isTooBright(
          meanThreshold: brightMeanThreshold,
          brightRatioThreshold: brightRatioThreshold,
        );
  }

  factory FlutterAnycamTfIlluminationScore.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfIlluminationScore(
      mean: (map['mean'] as num).toDouble(),
      darkPixelRatio: (map['darkPixelRatio'] as num).toDouble(),
      brightPixelRatio: (map['brightPixelRatio'] as num).toDouble(),
    );
  }
}
