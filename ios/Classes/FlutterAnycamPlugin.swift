import Flutter
import UIKit
import AVFoundation

public class FlutterAnycamPlugin: NSObject, FlutterPlugin {
    
    
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        
        let channel = FlutterMethodChannel(name: "br.dev.michaellopes.flutter_anycam/channel", binaryMessenger: registrar.messenger())
        
        let eventChannel = FlutterEventChannel(name: "br.dev.michaellopes.flutter_anycam/event", binaryMessenger: registrar.messenger())
        
        let viewFactory = FlutterViewFactory() as FlutterPlatformViewFactory;
        registrar.register(viewFactory, withId: "br.dev.michaellopes.flutter_anycam/view")
        
        let instance = FlutterAnycamPlugin()
        eventChannel.setStreamHandler(FlutterEventStreamChannel.shared)
        
        registrar.addMethodCallDelegate(instance, channel: channel)
        
    }
    
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "availableCameras":
            
            let cameras =  CameraUtil.availableCameras()
            DispatchQueue.main.async {
                result(cameras)
            }
            
            
        case "requestPermission":
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                DispatchQueue.main.async {
                    result(true)
                }
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
                
            default:
                DispatchQueue.main.async {
                    result(false)
                }
            }
        case "setFlash":
            let data =  call.arguments as? [String: Any?];
            let value = data?["value"] as? Bool
            AVCaptureUtil.shared.setFlash(value!)
            result(true)
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
                                                               quality: quality == 0 ? 0 : CGFloat(quality! / 100)
                                                               
            )
            
            DispatchQueue.main.async {
                result(res)
            }
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
