/// Normalized region of interest (0..1) for blur scoring on a TF frame.
class FlutterAnycamTfBlurRoi {
  const FlutterAnycamTfBlurRoi({
    required this.xMin,
    required this.yMin,
    required this.xMax,
    required this.yMax,
  });

  final double xMin;
  final double yMin;
  final double xMax;
  final double yMax;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'xMin': xMin,
      'yMin': yMin,
      'xMax': xMax,
      'yMax': yMax,
    };
  }

  factory FlutterAnycamTfBlurRoi.fromNormalizedBox({
    required double xMin,
    required double yMin,
    required double xMax,
    required double yMax,
  }) {
    return FlutterAnycamTfBlurRoi(
      xMin: xMin.clamp(0.0, 1.0),
      yMin: yMin.clamp(0.0, 1.0),
      xMax: xMax.clamp(0.0, 1.0),
      yMax: yMax.clamp(0.0, 1.0),
    );
  }
}
