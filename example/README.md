# flutter_anycam_example

Scenario hub for manual QA of `flutter_anycam`, plus integration tests.

## Run the app

```bash
cd example
flutter run
```

Open each scenario from the hub. Android-only tiles are disabled on iOS.

## Widget tests (no device camera required)

```bash
cd example
flutter test
```

## Integration tests (device or emulator)

```bash
cd example
flutter test integration_test/ -d <deviceId>
```

`scenario_catalog_test.dart` checks hub tiles + navigation.  
`camera_api_test.dart` calls `availableCameras()` and optionally mounts a preview when a camera exists.
