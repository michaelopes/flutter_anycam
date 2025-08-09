import Flutter
import UIKit

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
            let cameras = CameraUtil.availableCameras();
            result(cameras)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
