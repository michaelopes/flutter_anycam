# FlutterAnycam

Plugin Flutter para captura e análise de frames de múltiplos tipos de câmera (**frontal**, **traseira**, **USB** e **RTSP**), com suporte a conversão de frames para JPEG e processamento em tempo real.

---

## ✨ Recursos

- Listagem de câmeras disponíveis no dispositivo.
- Suporte a:
  - **Câmera traseira** (`back`)
  - **Câmera frontal** (`front`)
  - **Câmera USB** (`usb`) **`Somente android`**
  - **Fluxos de vídeo RTSP** (`rtsp`) **`Somente android`**
- Callback de frames para análise em tempo real.
- Conversão de frames para JPEG diretamente no Flutter.
- Pode ser executado multipas cameras ao mesmo tempo ex: Camera frontal e traseira
#### Obs: Não pode duas instâncias da mesma camera.
---

## 📦 Instalação

No `pubspec.yaml`:

```yaml
dependencies:
  flutter_anycam: ^[versão]
```

### Depois execute
flutter pub get

## Android
#### No arquivo AndroidManifest.xml adicione:
```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.USB_PERMISSION" />
    <uses-feature android:name="android.hardware.usb.host" />
    <uses-feature android:name="android.hardware.camera"/>
    <uses-feature android:name="android.hardware.camera.autofocus"/>
```

#### Dentro da tag da MainActivty adicione
```xml
 <intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
 </intent-filter>
```
## iOS
#### No arquivo Info.plist adicione:
```xml
<key>NSCameraUsageDescription</key>
<string>Sua camera será usada para realizar processamento e analise de dados</string>
<key>NSMicrophoneUsageDescription</key>
<string>Uso do microfone da camera</string>
```
## 📚 Frame data
#### Tipo de dados do frame
```dart 
final bytes = frame.bytes;
```
#### Os bytes no Android é nv21 enquanto no iOS bra8888

## 📚 API
#### Métodos principais

| Método      | Descrição     | Disponibilidade      |
|---------------|---------------|---------------|
| FlutterAnycam.availableCameras() | Lista todas as câmeras disponíveis (frontal, traseira, USB). | Android, iOS |
| FlutterAnycamCameraSelector.rtsp({url, username, password}) | Cria um seletor para fluxo RTSP. | Android |
| FlutterAnycamWidget(camera, onFrame) | Exibe a câmera selecionada e envia frames para o callback. | Android, iOS |
| FlutterAnycam.frameConversor.convertToJpeg(frame, rotation) | Converte um frame capturado para JPEG. | Android, iOS |

