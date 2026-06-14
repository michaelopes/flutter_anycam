# WebRTC — Integração nativa com FlutterAnycam

O módulo WebRTC faz parte do `flutter_anycam` (Android), no mesmo padrão do TensorFlow. Ele permite transmitir vídeo da câmera para um peer remoto via WebRTC, com o pipeline de frames executado **inteiramente na camada nativa Android** — sem passar frames pelo bridge Flutter.

---

## Visão geral

### Modo recomendado (nativo)

```
Câmera (CameraX) → CameraRawStream → WebRtcStreamHandler → PeerConnection → remoto
```

O Flutter fica responsável apenas por **signaling** (SDP, ICE, DataChannel).

### Modo manual (fallback)

```
Câmera → EventChannel → Flutter → MethodChannel → WebRtcStreamHandler
```

Útil para debug ou casos em que você precisa inspecionar/processar frames no Dart antes de enviar.

---

## Requisitos

| Item | Detalhe |
|------|---------|
| Plataforma | **Android** (WebRTC nativo ainda não disponível no iOS) |
| Dependência | Apenas `flutter_anycam` — não é necessário `fp_flutter_webrtc` |
| Câmera ativa | Um `FlutterAnycamWidget` (ou sessão equivalente) deve estar aberto com a mesma `cameraId` |
| Permissões | `CAMERA` + `RECORD_AUDIO` (microfone usado pelo WebRTC) |

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.INTERNET" />
```

### Info.plist (iOS — apenas se usar outras features do anycam)

```xml
<key>NSCameraUsageDescription</key>
<string>...</string>
<key>NSMicrophoneUsageDescription</key>
<string>...</string>
```

---

## Instalação

```yaml
dependencies:
  flutter_anycam: ^1.1.13  # ou path/git conforme seu projeto
```

```dart
import 'package:flutter_anycam/flutter_anycam.dart';
```

Classes exportadas:

| Classe | Papel |
|--------|-------|
| `FlutterAnycamWebRtc` | Factory de streams e `pushFrame` manual |
| `FlutterAnycamWebRtcStream` | Stream individual (signaling) |
| `FlutterAnycamWebRtcCameraFeed` | Liga câmera → WebRTC no nativo |
| `FlutterAnycamWebRtcCallbacks` | Callbacks de conexão, ICE e DataChannel |
| `FlutterAnycamWebRtcIceServer` | Configuração de STUN/TURN |
| `FlutterAnycamWebRtcI420Image` | Frame I420 para `pushFrame` manual |

---

## Integração passo a passo

### 1. Listar câmeras e abrir preview

A câmera precisa estar capturando frames para o feed nativo funcionar.

```dart
final cameras = await FlutterAnycam.availableCameras();
final camera = cameras.first;

// No widget tree:
FlutterAnycamWidget(
  camera: camera,
  fps: 15,
  onFrame: (_) {}, // preview opcional
)
```

### 2. Criar stream WebRTC

```dart
FlutterAnycamWebRtcStream? stream;
FlutterAnycamWebRtcCameraFeedDisposer? feedDisposer;

stream = await FlutterAnycamWebRtc.I.newStream(
  iceServers: [
    FlutterAnycamWebRtcIceServer(
      hostname: 'stun:stun.l.google.com:19302',
      username: null,
      password: null,
    ),
  ],
  callbacks: FlutterAnycamWebRtcCallbacks(
    onConnectedCallback: () async {
      // 3. Ligar câmera nativamente ao stream
      feedDisposer = await FlutterAnycamWebRtcCameraFeed.I.attach(
        cameraId: camera.id,
        fps: 15,
        streamId: stream!.id,
      );
    },
    onDisconnectedCallback: () async {
      await feedDisposer?.call();
      feedDisposer = null;
    },
    onCandidateCallback: (candidate) {
      // Enviar ICE candidate para o peer remoto (browser, outro device, etc.)
      sendCandidateToRemote(candidate);
    },
    onDataMessageCallback: (data) {
      // Eco ou processe mensagens do DataChannel
      stream?.sendDataMessage(data ?? {});
    },
  ),
);
```

> **Importante:** use a mesma `cameraId` do `FlutterAnycamWidget` no `attach()`.

### 3. Signaling — receber offer e responder

Quando o peer remoto envia um SDP offer:

```dart
final answer = await stream!.createAnswer(offerSdp);
// Retorne answer ao remoto: { "type": "answer", "sdp": "..." }
```

### 4. Signaling — ICE candidates

Quando o remoto envia candidates:

```dart
await stream!.addCandidate({
  'sdpMid': candidate['sdpMid'],
  'sdpMLineIndex': candidate['sdpMLineIndex'],
  'candidate': candidate['candidate'],
});
```

Quando o Android gera candidates (`onCandidateCallback`), repasse ao remoto.

### 5. DataChannel

```dart
await stream!.sendDataMessage({'action': 'ping'});
```

### 6. Cleanup

```dart
await feedDisposer?.call();
await stream?.dispose();
```

---

## Exemplo completo (servidor HTTP + WebRTC)

Referência: `fp-flutter-webrtc/example/lib/app_server.dart` (atualizado para usar apenas `flutter_anycam`).

```dart
class AppServer {
  FlutterAnycamWebRtcStream? _stream;
  FlutterAnycamWebRtcCameraFeedDisposer? _feedDisposer;

  Future<void> start({required String cameraId}) async {
    _stream = await FlutterAnycamWebRtc.I.newStream(
      callbacks: FlutterAnycamWebRtcCallbacks(
        onConnectedCallback: () async {
          _feedDisposer = await FlutterAnycamWebRtcCameraFeed.I.attach(
            cameraId: cameraId,
            fps: 15,
            streamId: _stream!.id,
          );
        },
        onDisconnectedCallback: () async {
          await _feedDisposer?.call();
          _feedDisposer = null;
        },
        onDataMessageCallback: (data) {
          _stream?.sendDataMessage(data ?? {});
        },
      ),
    );

    // Endpoints HTTP para offer/answer e ICE candidates...
  }
}
```

No `main.dart`:

```dart
final cameras = await FlutterAnycam.availableCameras();

AppServer.I.start(cameraId: cameras.first.id);

// Widget com a mesma câmera:
FlutterAnycamWidget(camera: cameras.first, fps: 15)
```

---

## API de referência

### `FlutterAnycamWebRtc.I.newStream`

Cria um peer WebRTC no nativo e retorna um `FlutterAnycamWebRtcStream`.

```dart
Future<FlutterAnycamWebRtcStream?> newStream({
  FlutterAnycamWebRtcCallbacks? callbacks,
  List<FlutterAnycamWebRtcIceServer>? iceServers,
})
```

### `FlutterAnycamWebRtcCameraFeed.I.attach`

Registra feed nativo câmera → WebRTC. **Substitui** o antigo par:

```dart
// ❌ Antigo (ineficiente)
FlutterAnycamCameraRawStream.I.register(..., listener: (frame) {
  FpFlutterWebrtc.I.pushFrame(...);
});

// ✅ Novo (nativo)
FlutterAnycamWebRtcCameraFeed.I.attach(
  cameraId: '0',
  fps: 15,
  streamId: stream.id,
);
```

Parâmetros:

| Parâmetro | Tipo | Descrição |
|-----------|------|-----------|
| `cameraId` | `String` | ID da câmera (mesmo do `FlutterAnycamWidget`) |
| `fps` | `int` | Taxa máxima de frames enviados ao WebRTC |
| `streamId` | `String` | ID retornado por `newStream()` |
| `alsoDeliverToFlutter` | `bool` | Se `true`, também envia frames via EventChannel (padrão: `false`) |

Retorna um `disposer` — chame ao desconectar:

```dart
await feedDisposer?.call();
// ou
await FlutterAnycamWebRtcCameraFeed.I.detach(cameraId: camera.id);
```

### `FlutterAnycamWebRtcStream`

| Método | Descrição |
|--------|-----------|
| `id` | ID do stream |
| `createAnswer(offerSdp, {iceServers})` | Processa SDP offer e retorna answer |
| `addCandidate(data)` | Adiciona ICE candidate remoto |
| `sendDataMessage(data)` | Envia JSON via DataChannel |
| `dispose()` | Para o stream e remove callbacks |

### `FlutterAnycamWebRtcCallbacks`

| Callback | Quando dispara |
|----------|----------------|
| `onConnectedCallback` | ICE conectado — **melhor momento para chamar `attach()`** |
| `onDisconnectedCallback` | Peer desconectou — chame `feedDisposer` aqui |
| `onCandidateCallback` | Novo ICE candidate gerado localmente |
| `onDataMessageCallback` | Mensagem recebida no DataChannel |

### `FlutterAnycamWebRtc.I.pushFrame` (manual)

Envia um frame I420 manualmente via MethodChannel. Mantido para compatibilidade e debug.

```dart
await FlutterAnycamWebRtc.I.pushFrame(
  FlutterAnycamWebRtcI420Image(
    width: frame.width,
    height: frame.height,
    rotation: frame.rotation,
    dataY: frame.planes[0].bytes,
    strideY: frame.planes[0].rowStride,
    dataU: frame.planes[1].bytes,
    strideU: frame.planes[1].rowStride,
    dataV: frame.planes[2].bytes,
    strideV: frame.planes[2].rowStride,
    pixelStrideU: frame.planes[1].pixelStride,
    pixelStrideV: frame.planes[2].pixelStride,
  ),
  streamId: stream.id, // opcional — omitir envia para todos os streams ativos
);
```

Combinável com raw stream Flutter:

```dart
await FlutterAnycamCameraRawStream.I.register(
  cameraId: camera.id,
  fps: 15,
  listener: (frame) {
    FlutterAnycamWebRtc.I.pushFrame(/* ... */);
  },
);
```

---

## Migração de `fp_flutter_webrtc`

| Antes (`fp_flutter_webrtc`) | Depois (`flutter_anycam`) |
|-----------------------------|---------------------------|
| `import 'package:fp_flutter_webrtc/...'` | `import 'package:flutter_anycam/flutter_anycam.dart'` |
| `FpFlutterWebrtc.I.newStream()` | `FlutterAnycamWebRtc.I.newStream()` |
| `FpFlutterWbrtcStreamer` | `FlutterAnycamWebRtcStream` |
| `FpFlutterWebrtcCallbacks` | `FlutterAnycamWebRtcCallbacks` |
| `FpFlutterWebrtcIceServer` | `FlutterAnycamWebRtcIceServer` |
| `FpFlutterWebrtcI420Image` | `FlutterAnycamWebRtcI420Image` |
| `register` + `pushFrame` no Flutter | `FlutterAnycamWebRtcCameraFeed.I.attach()` |

Remova do `pubspec.yaml`:

```yaml
# fp_flutter_webrtc:   ← remover
#   path: ...
```

---

## Arquitetura interna (Android)

```
android/src/main/java/.../flutter_anycam/
├── webrtc/
│   ├── WebRtcStreamHandler.java    # Singleton — gerencia PeerConnectionFactory e streams
│   ├── WebRtcStreamer.java         # PeerConnection, vídeo, áudio, DataChannel
│   ├── I420Image.java              # Buffer YUV I420
│   ├── YuvUtil.java                # Normalização YUV (JNI)
│   └── WebRtcImageUtil.java        # ImageProxy → I420Image
└── stream/
    ├── CameraStreamManager.java    # registerWebRtcCameraFeed / removeWebRtcFeed
    └── CameraRawStream.java        # Entrega frames ao WebRTC e/ou Flutter
```

MethodChannel (mesmo canal do anycam):

| Método | Direção | Descrição |
|--------|---------|-----------|
| `newWebRtcStream` | Dart → Native | Cria stream |
| `stopWebRtcStream` | Dart → Native | Para stream |
| `createWebRtcAnswer` | Dart → Native | SDP offer → answer |
| `addWebRtcCandidate` | Dart → Native | ICE candidate remoto |
| `sendWebRtcDataMessage` | Dart → Native | DataChannel outbound |
| `pushWebRtcFrame` | Dart → Native | Frame manual |
| `registerWebRtcCameraFeed` | Dart → Native | Liga câmera nativamente |
| `disposeWebRtcCameraFeed` | Dart → Native | Desliga feed nativo |
| `onWebRtcConnected` | Native → Dart | Callback |
| `onWebRtcDisconnected` | Native → Dart | Callback |
| `onWebRtcIceCandidate` | Native → Dart | Callback |
| `onWebRtcDataChannelMessage` | Native → Dart | Callback |

---

## Limitações e observações

1. **Somente câmeras internas (front/back)** — o feed nativo usa `DeviceCamera` via `CameraStreamManager`. USB e RTSP ainda não estão integrados ao pipeline WebRTC.

2. **Câmera deve estar ativa** — `attach()` registra o sink, mas frames só fluem se a câmera estiver aberta (via `FlutterAnycamWidget`).

3. **Conecte o feed após `onConnectedCallback`** — `WebRtcStreamer.pushFrame` só envia frames quando o peer está conectado (`started && connected`).

4. **Rotação** — no modo nativo, a rotação vem de `DeviceCamera.getCustomRotationDegrees()` automaticamente. No modo manual, informe `rotation` corretamente no `FlutterAnycamWebRtcI420Image`.

5. **iOS** — signaling e feed nativo não estão implementados. Use fallback manual ou aguarde implementação futura.

6. **Performance** — o modo nativo elimina 2 crossings do Flutter bridge e ~4 cópias de buffer por frame em relação ao fluxo antigo `registerRawStream` + `pushFrame`.

---

## Troubleshooting

| Problema | Causa provável | Solução |
|----------|----------------|---------|
| Vídeo preto no remoto | Feed attachado antes da conexão ICE | Chame `attach()` em `onConnectedCallback` |
| Nenhum frame enviado | Câmera não aberta ou `cameraId` diferente | Abra `FlutterAnycamWidget` com a mesma `cameraId` |
| Áudio mudo | Permissão `RECORD_AUDIO` ausente | Adicionar permissão no manifest e solicitar em runtime |
| ICE não conecta | STUN/TURN incorreto ou rede restrita | Configurar `FlutterAnycamWebRtcIceServer` com TURN |
| Crash ao desconectar | Feed não liberado | Chamar `feedDisposer` em `onDisconnectedCallback` |

---

## Fluxo recomendado (diagrama)

```
┌─────────────┐     newStream()      ┌──────────────────┐
│   Flutter   │ ───────────────────► │ WebRtcStreamHandler │
│  (signaling)│ ◄── onConnected ──── │   (Android nativo)  │
└─────────────┘                      └────────┬─────────┘
       │                                        │
       │ attach(cameraId, streamId)             │ pushFrame (I420)
       ▼                                        ▼
┌─────────────┐     ImageProxy         ┌──────────────────┐
│ FlutterAnycam│ ◄── CameraX ──────── │ CameraRawStream  │
│   Widget    │                        └──────────────────┘
└─────────────┘
```
