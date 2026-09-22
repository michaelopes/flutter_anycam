enum FlutterAnycamTfDelegate {
  cpu("cpu"),
  gpu("gpu"),
  nnapi("nnapi"),
  xnnpack("xnnpack");

  final String value;
  const FlutterAnycamTfDelegate(this.value);

  static FlutterAnycamTfDelegate? fromCode(String value) {
    for (final item in values) {
      if (item.value == value) return item;
    }
    return null;
  }
}
