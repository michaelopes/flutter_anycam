enum FlutterAnycamFilter {
  none(0),
  grayscale(1),
  grayscaleLowContrast(2),
  grayscaleMediumContrast(3),
  grayscaleHighContrast(4);

  final int code;
  const FlutterAnycamFilter(this.code);

  static FlutterAnycamFilter? fromCode(int code) {
    for (final item in values) {
      if (item.code == code) return item;
    }
    return null;
  }
}
