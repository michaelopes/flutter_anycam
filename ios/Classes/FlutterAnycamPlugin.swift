import Flutter
import UIKit
import AVFoundation

public class FlutterAnycamPlugin: NSObject, FlutterPlugin {

    /// Pixel / TFLite work: never on main, so the UI and Flutter's raster pipeline keep going.
    private let workQueue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.work", qos: .userInitiated, attributes: .concurrent)
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        CameraViewFactory.shared.load(textureRegistry: registrar.textures());
        
        let channel = FlutterMethodChannel(name: "br.dev.michaellopes.flutter_anycam/channel", binaryMessenger: registrar.messenger())
        
        let eventChannel = FlutterEventChannel(name: "br.dev.michaellopes.flutter_anycam/event", binaryMessenger: registrar.messenger())
        
        TfModelHandler.shared.configure(channel: channel) { assetPath in
            registrar.lookupKey(forAsset: assetPath)
        }
        
        let instance = FlutterAnycamPlugin()
        eventChannel.setStreamHandler(FlutterEventStreamChannel.shared)
        
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.publish(instance)
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        CameraViewFactory.shared.disposeAll()
        TfModelHandler.shared.disposeAll()
        TfFrameHandler.shared.closeAll()
    }

    private func background(_ result: @escaping FlutterResult, _ work: @escaping () -> Any?) {
        workQueue.async {
            let value = work()
            DispatchQueue.main.async { result(value) }
        }
    }

    private static func roi(_ data: [String: Any]) -> (Double, Double, Double, Double) {
        var r = (-1.0, -1.0, -1.0, -1.0)
        if let roi = data["roi"] as? [String: Any] {
            if let v = roi["xMin"] as? NSNumber { r.0 = v.doubleValue }
            if let v = roi["yMin"] as? NSNumber { r.1 = v.doubleValue }
            if let v = roi["xMax"] as? NSNumber { r.2 = v.doubleValue }
            if let v = roi["yMax"] as? NSNumber { r.3 = v.doubleValue }
        }
        return r
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "createView":
            guard let data = call.arguments as? [String: Any?],
                  let id = CameraViewFactory.shared.createView(args: data) else {
                result(FlutterError(code: "createView", message: "Invalid camera arguments", details: nil))
                return
            }
            result(id)
        case "disposeView":
            if let data = call.arguments as? [String: Any?] {
                CameraViewFactory.shared.disposeView(args: data)
            }
            result(true)
        case "availableCameras":
            background(result) { CameraUtil.availableCameras() }
        case "broadcastPermissionGranted":
            CameraViewFactory.shared.broadcastPermissionGranted()
            result(true);
        case "requestPermission":
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                result(true)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async { result(granted) }
                }
            default:
                result(false)
            }
        case "setFlash":
            let data = call.arguments as? [String: Any?]
            AVCaptureUtil.shared.setFlash((data?["value"] as? Bool) ?? false)
            result(true)
        case "setZoom":
            let data = call.arguments as? [String: Any?]
            if let cameraId = data?["cameraId"] as? String,
               let camera = CameraViewFactory.shared.getCameraById(id: cameraId) {
                let value = (data?["zoom"] as? NSNumber)?.floatValue ?? 1.0
                camera.setZoom(zoom: value)
            }
            result(true)
        case "setExposureCompensation":
            let data = call.arguments as? [String: Any?]
            if let cameraId = data?["cameraId"] as? String,
               let camera = CameraViewFactory.shared.getCameraById(id: cameraId) {
                camera.setExposureCompensation(value: (data?["value"] as? NSNumber)?.intValue ?? 0)
            }
            result(true)
        case "convertBGRA8888ToJpeg":
            guard let data = call.arguments as? [String: Any?],
                  let bytes = data["bytes"] as? FlutterStandardTypedData,
                  let width = data["width"] as? Int,
                  let height = data["height"] as? Int else {
                result(nil)
                return
            }
            let quality = min(max((data["quality"] as? Int) ?? 100, 0), 100)
            let filter = data["filter"] as? Int ?? 0
            let crop = data["crop"] as? [String: Any]
            background(result) {
                let jpeg = ImageConverterUtil.convertBGRA8888ToJPEG(
                    bgraData: bytes.data,
                    width: width,
                    height: height,
                    quality: CGFloat(quality) / 100.0,
                    filter: filter,
                    crop: crop
                )
                return jpeg.map { FlutterStandardTypedData(bytes: $0) }
            }
        case "loadTfModel":
            guard let data = call.arguments as? [String: Any],
                  let assetPath = data["assetPath"] as? String,
                  let key = data["key"] as? String else {
                result(FlutterError(code: "bad_args", message: "loadTfModel", details: nil))
                return
            }
            let delegate = data["delegate"] as? String ?? "cpu"
            let threads = (data["threads"] as? NSNumber)?.intValue ?? 1
            workQueue.async {
                do {
                    try TfModelHandler.shared.loadModel(assetPath: assetPath, key: key, delegate: delegate, threads: threads)
                    DispatchQueue.main.async { result(true) }
                } catch {
                    DispatchQueue.main.async {
                        result(FlutterError(code: "loadTfModel", message: error.localizedDescription, details: nil))
                    }
                }
            }
        case "runTfInference":
            guard let args = call.arguments as? [String: Any] else {
                result(FlutterError(code: "bad_args", message: "runTfInference", details: nil))
                return
            }
            TfModelHandler.shared.runInference(arguments: args, result: result)
        case "disposeTfModel":
            guard let data = call.arguments as? [String: Any], let key = data["key"] as? String else {
                result(false)
                return
            }
            background(result) {
                TfModelHandler.shared.disposeModel(key: key)
                return true
            }
        case "closeTfFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(false)
                return
            }
            TfFrameHandler.shared.closeFrame(id)
            result(true)
        case "getTfFrameJpeg":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            background(result) {
                guard let bytes = TfFrameHandler.shared.getFrameJpeg(frameId: id) else { return nil }
                return ["bytes": FlutterStandardTypedData(bytes: bytes)]
            }
        case "registerTfFrameFromJpeg":
            guard let data = call.arguments as? [String: Any],
                  let typed = data["jpegBytes"] as? FlutterStandardTypedData else {
                result(nil)
                return
            }
            background(result) { TfFrameHandler.shared.registerFrameFromJpeg(typed.data) }
        case "registerTfFrameCopy":
            guard let data = call.arguments as? [String: Any],
                  let frameId = data["frameId"] as? String else {
                result(nil)
                return
            }
            background(result) { TfFrameHandler.shared.registerFrameCopy(frameId) }
        case "registerTfFrameCrop":
            guard let data = call.arguments as? [String: Any],
                  let frameId = data["frameId"] as? String,
                  let x = data["x"] as? NSNumber,
                  let y = data["y"] as? NSNumber,
                  let width = data["width"] as? NSNumber,
                  let height = data["height"] as? NSNumber else {
                result(nil)
                return
            }
            var resizeWidth: Int?
            var resizeHeight: Int?
            if let resizeTo = data["resizeTo"] as? [String: Any] {
                resizeWidth = (resizeTo["width"] as? NSNumber)?.intValue
                resizeHeight = (resizeTo["height"] as? NSNumber)?.intValue
            }
            background(result) {
                TfFrameHandler.shared.registerFrameCrop(
                    frameId,
                    x: x.intValue,
                    y: y.intValue,
                    width: width.intValue,
                    height: height.intValue,
                    resizeWidth: resizeWidth,
                    resizeHeight: resizeHeight
                )
            }
        case "getTfFrameBlurScore":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            let sampleStep = (data["sampleStep"] as? NSNumber)?.intValue ?? 2
            let r = Self.roi(data)
            background(result) {
                TfFrameHandler.shared.computeBlurScore(
                    frameId: id, xMin: r.0, yMin: r.1, xMax: r.2, yMax: r.3, sampleStep: sampleStep
                )
            }
        case "getTfFrameIlluminationScore":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            let sampleStep = (data["sampleStep"] as? NSNumber)?.intValue ?? 2
            let r = Self.roi(data)
            background(result) {
                TfFrameHandler.shared.computeIlluminationScore(
                    frameId: id, xMin: r.0, yMin: r.1, xMax: r.2, yMax: r.3, sampleStep: sampleStep
                )
            }
        case "closeTfInferenceResult":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(false)
                return
            }
            TfModelHandler.shared.closeInferenceResult(id: id)
            result(true)
        case "getInferenceResultScaledCroppedFrame":
            guard let d = call.arguments as? [String: Any], let id = d["id"] as? String else {
                result(nil)
                return
            }
            background(result) { TfModelHandler.shared.getScaledCroppedFrameMap(inferenceId: id) }
        case "getInferenceResultCroppedFrame":
            guard let d = call.arguments as? [String: Any], let id = d["id"] as? String else {
                result(nil)
                return
            }
            background(result) { TfModelHandler.shared.getCroppedFrameMap(inferenceId: id) }
        case "getInferenceResultInferenceFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            result(TfModelHandler.shared.frameMapForInference(id: id, kind: TfModelHandler.FrameKind.inference))
        case "getInferenceResultRawFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            result(TfModelHandler.shared.frameMapForInference(id: id, kind: TfModelHandler.FrameKind.raw))

        // Android-only features: answer like "not available" instead of throwing
        // MissingPluginException into app code.
        case "registerRawStream", "registerTfCameraStream", "registerWebRtcCameraFeed":
            result(false)
        case "disposeRawStream", "disposeTfCameraStream", "disposeWebRtcCameraFeed", "stopWebRtcStream":
            result(true)
        case "newWebRtcStream", "createWebRtcAnswer", "addWebRtcCandidate",
             "pushWebRtcFrame", "sendWebRtcDataMessage", "convertNv21ToJpeg":
            result(FlutterError(code: "unsupported", message: "\(call.method) is not available on iOS", details: nil))
        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
