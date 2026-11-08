## 1.0.0

* Plugin Flutter para captura e análise de frames de múltiplos tipos de câmera (frontal, traseira, USB e RTSP), com suporte a conversão de frames para JPEG e processamento em tempo real.

## 1.0.1
Correções de multi-camera android

## 1.0.2
Correções no gerênciamento de instâncias multi-componetes da camera android

## 1.0.3
Correções dispose da camera

## 1.0.4
Correção de rotação em cameras android

## 1.0.5
Motor de conversão de imagem nativa

## 1.0.6
Sincronização na criação de cameras android

## 1.0.7
Melhoria no gerênciamento multi-camera android

## 1.0.8
Correção de conversão de imagem nv21 para jpg

## 1.0.9
Adicionado controle de flash

## 1.0.10
Correção no sistema de permissão da camera android

## 1.0.11
Suporte a dispositivos de 16 KB

## 1.0.12
Sistema de permissões aprimorado

## 1.0.13
Suporte a preferredSize para seleção de resolução dos frames

## 1.1.0
Migração de AndroidView e UiKitView para Texture
- Melhoria de performance
- Maior estabilidade da usabilidade


## 1.1.1
Correção da proporção do preview no iOS

## 1.1.2
Textos e traduções

## 1.1.3
Correção refresh camera USB

## 1.1.4
Correção buffer textura camera USB

## 1.1.5
Corrreções no dispose camera USB

## 1.1.6
Implementação de previewScale

## 1.1.7
Correção para iPhones mais antigos

## 1.1.8
Adição de controle de thread e fila para o gerênciamento de frames no Android

## 1.1.9
Melhorias no gerênciamento de threads

## 1.1.10
Bug fix camera usb

## 1.1.11
Adicionado suporte para que terceiros possam realizar integrações externas ao plugin v1.0

## 1.1.12
Melhorias suporte na integrações externas

## 1.1.13
RTSP camera support has been discontinued in this package because it does not comply with the new 16 KB paging requirement mandated by the Play Store.
## Unreleased
Estabilidade e memória (Android e iOS):
* Frames TF com dono único: são liberados nativamente quando nenhum listener Dart os recebe, quando o `FlutterAnycamTfWidget` os descarta (`maxConcurrentFrames`) ou quando `onFrame` lança erro. Limite de 12 frames de câmera vivos.
* Quando duas telas usam a mesma câmera, o frame TF vai só para a view mais recente (a rota do topo); frames NV21/BGRA continuam indo para todas.
* Todas as respostas do method channel acontecem uma única vez, na main thread; o trabalho pesado roda em background. `processTfOutput` tem timeout de 10 s.
* Android: eventos de câmera são roteados para o engine Flutter que criou a view (múltiplos engines). `createView` falha se a câmera estiver em uso por um `FlutterAnycamTfCameraStream`.
* iOS: sessão de captura sempre fecha a configuração (sem preview preto), processamento de frame fora da fila de captura, recuperação de erro de runtime da sessão e, em aparelhos sem multi-câmera, a câmera anterior é retomada quando a nova é liberada.

Mudanças de comportamento:
* `loadModel` com falha lança `PlatformException` (antes podia derrubar o app no Android).
* iOS: frames TF chegam ao Dart só com `id`/`width`/`height`; use `getJpeg()`/inferência para os pixels.
* Android: frames NV21 redimensionados informam `rowStride == width` e `pixelStride == 1`.
* iOS: métodos exclusivos de Android retornam `false`/`true` ou `PlatformException('unsupported')` em vez de `MissingPluginException`.
* `FlutterAnycamTfSession.dispose()` e `FlutterAnycamTfInferenceResult.close()` são idempotentes e não lançam; `runInference` numa sessão descartada lança `StateError`.
