import Flutter
import UIKit
import AVFoundation

public class FlutterAnycamPlugin: NSObject, FlutterPlugin {
    
    
    
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
    }
    
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "createView":
            let data =  call.arguments as? [String: Any?];
            let id = CameraViewFactory.shared.createView(args: data!)
            DispatchQueue.main.async {
                result(id)
            }
            break;
        case "disposeView":
            let data =  call.arguments as? [String: Any?];
            CameraViewFactory.shared.disposeView(args: data!)
            DispatchQueue.main.async {
                result(true)
            }
            break;
        case "availableCameras":
            
            let cameras =  CameraUtil.availableCameras()
            DispatchQueue.main.async {
                result(cameras)
            }
            break;
        case "broadcastPermissionGranted":
            CameraViewFactory.shared.broadcastPermissionGranted()
            result(true);
            break;
        case "requestPermission":
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                DispatchQueue.main.async {
                    result(true)
                }
                break;
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            DispatchQueue.main.async {
                                result(true)
                            }
                        } else {
                            DispatchQueue.main.async {
                                result(false)
                            }
                        }
                    }
                }
                break;
            default:
                DispatchQueue.main.async {
                    result(false)
                }
            }
            break;
        case "setFlash":
            let data =  call.arguments as? [String: Any?];
            let value = data?["value"] as? Bool
            AVCaptureUtil.shared.setFlash(value!)
            result(true)
            break;
            
        case "setZoom":
            let data =  call.arguments as? [String: Any?];
            let value = data?["zoom"] as? Double
            let cameraId = data?["cameraId"] as? String
            let camera =  CameraViewFactory.shared.getCameraById(id: cameraId!);
            if(camera != nil) {
                camera?.setZoom(zoom: Float(value ?? 1.0));
            }
            result(true)
            break;
        case "convertBGRA8888ToJpeg":
            
            let data =  call.arguments as? [String: Any?];
            if(data == nil) {
                DispatchQueue.main.async {
                    result(nil)
                }
                return;
            }
            
            let bytes = data?["bytes"] as? FlutterStandardTypedData
            let width = data?["width"] as? Int
            let height = data?["height"] as? Int
            let quality = data?["quality"] as? Int
            let rotation = data?["rotation"] as? Int
            let filter = data?["filter"] as? Int ?? 0
            let crop = data?["crop"] as? [String: Any]
            
            if(bytes == nil || width == nil || height == nil || quality == nil || rotation == nil) {
                DispatchQueue.main.async {
                    result(nil)
                }
                return;
            }
            
            let bgraData = bytes!.data
            let res = ImageConverterUtil.convertBGRA8888ToJPEG(bgraData: bgraData,
                                                               width: width!,
                                                               height: height!,
                                                               quality: quality == 0 ? 0 : CGFloat(quality! / 100),
                                                               filter: filter,
                                                               crop: crop
                                                               
            )
            
            DispatchQueue.main.async {
                result(res)
            }
            break;
        case "loadTfModel":
            guard let data = call.arguments as? [String: Any],
                  let assetPath = data["assetPath"] as? String,
                  let key = data["key"] as? String,
                  let delegate = data["delegate"] as? String else {
                result(FlutterError(code: "bad_args", message: "loadTfModel", details: nil))
                return
            }
            let threads = (data["threads"] as? NSNumber)?.intValue ?? 1
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try TfModelHandler.shared.loadModel(assetPath: assetPath, key: key, delegate: delegate, threads: threads)
                    DispatchQueue.main.async { result(true) }
                } catch {
                    DispatchQueue.main.async {
                        result(FlutterError(code: "loadTfModel", message: error.localizedDescription, details: nil))
                    }
                }
            }
            break
        case "runTfInference":
            guard let args = call.arguments as? [String: Any] else {
                result(FlutterError(code: "bad_args", message: "runTfInference", details: nil))
                return
            }
            TfModelHandler.shared.runInference(arguments: args, result: result)
            break
        case "disposeTfModel":
            guard let data = call.arguments as? [String: Any], let key = data["key"] as? String else {
                result(false)
                return
            }
            TfModelHandler.shared.disposeModel(key: key)
            result(true)
            break
        case "closeTfFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(false)
                return
            }
            TfFrameHandler.shared.closeFrame(id)
            result(true)
            break
        case "getTfFrameJpeg":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            if let bytes = TfFrameHandler.shared.getFrameJpeg(frameId: id) {
                result(["bytes": FlutterStandardTypedData(bytes: bytes)])
            } else {
                result(nil)
            }
            break
        case "registerTfFrameFromJpeg":
            guard let data = call.arguments as? [String: Any],
                  let typed = data["jpegBytes"] as? FlutterStandardTypedData else {
                result(nil)
                return
            }
            result(TfFrameHandler.shared.registerFrameFromJpeg(typed.data))
            break
        case "registerTfFrameCopy":
            guard let data = call.arguments as? [String: Any],
                  let frameId = data["frameId"] as? String else {
                result(nil)
                return
            }
            result(TfFrameHandler.shared.registerFrameCopy(frameId))
            break
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
            result(
                TfFrameHandler.shared.registerFrameCrop(
                    frameId,
                    x: x.intValue,
                    y: y.intValue,
                    width: width.intValue,
                    height: height.intValue,
                    resizeWidth: resizeWidth,
                    resizeHeight: resizeHeight
                )
            )
            break
        case "getTfFrameBlurScore":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            let sampleStep = (data["sampleStep"] as? NSNumber)?.intValue ?? 2
            var xMin = -1.0
            var yMin = -1.0
            var xMax = -1.0
            var yMax = -1.0
            if let roi = data["roi"] as? [String: Any] {
                if let v = roi["xMin"] as? NSNumber { xMin = v.doubleValue }
                if let v = roi["yMin"] as? NSNumber { yMin = v.doubleValue }
                if let v = roi["xMax"] as? NSNumber { xMax = v.doubleValue }
                if let v = roi["yMax"] as? NSNumber { yMax = v.doubleValue }
            }
            let score = TfFrameHandler.shared.computeBlurScore(
                frameId: id,
                xMin: xMin,
                yMin: yMin,
                xMax: xMax,
                yMax: yMax,
                sampleStep: sampleStep
            )
            result(score)
            break
        case "getTfFrameIlluminationScore":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            let sampleStep = (data["sampleStep"] as? NSNumber)?.intValue ?? 2
            var xMin = -1.0
            var yMin = -1.0
            var xMax = -1.0
            var yMax = -1.0
            if let roi = data["roi"] as? [String: Any] {
                if let v = roi["xMin"] as? NSNumber { xMin = v.doubleValue }
                if let v = roi["yMin"] as? NSNumber { yMin = v.doubleValue }
                if let v = roi["xMax"] as? NSNumber { xMax = v.doubleValue }
                if let v = roi["yMax"] as? NSNumber { yMax = v.doubleValue }
            }
            result(TfFrameHandler.shared.computeIlluminationScore(
                frameId: id,
                xMin: xMin,
                yMin: yMin,
                xMax: xMax,
                yMax: yMax,
                sampleStep: sampleStep
            ))
            break
        case "closeTfInferenceResult":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(false)
                return
            }
            TfModelHandler.shared.closeInferenceResult(id: id)
            result(true)
            break
        case "getInferenceResultScaledCroppedFrame":
            guard let d = call.arguments as? [String: Any], let id = d["id"] as? String else {
                result(nil)
                return
            }
            result(TfModelHandler.shared.getScaledCroppedFrameMap(inferenceId: id))
            break
        case "getInferenceResultCroppedFrame":
            guard let d = call.arguments as? [String: Any], let id = d["id"] as? String else {
                result(nil)
                return
            }
            result(TfModelHandler.shared.getCroppedFrameMap(inferenceId: id))
            break
        case "getInferenceResultInferenceFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            if let m = TfModelHandler.shared.frameMapForInference(id: id, kind: TfModelHandler.FrameKind.inference) {
                result(m)
            } else {
                result(nil)
            }
            break
        case "getInferenceResultRawFrame":
            guard let data = call.arguments as? [String: Any], let id = data["id"] as? String else {
                result(nil)
                return
            }
            if let m = TfModelHandler.shared.frameMapForInference(id: id, kind: TfModelHandler.FrameKind.raw) {
                result(m)
            } else {
                result(nil)
            }
            break
        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
