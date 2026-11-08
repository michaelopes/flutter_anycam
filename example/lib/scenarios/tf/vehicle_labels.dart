class VehicleLabels {
  static final Map<int, String> labels = {
    0: 'car',
    1: 'motorcycle',
    2: 'bus',
    3: 'truck',
  };

  static String getLabel(num id) => labels[id.toInt()] ?? 'unknown';

  static bool isVehicle(num id) => labels.containsKey(id.toInt());
}
