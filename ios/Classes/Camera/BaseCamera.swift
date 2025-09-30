//
//  BaseCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import AVFoundation
import Flutter

class BaseCamera: NSObject, FlutterTexture {
    
    static var waitPermission = false;
    var latestPixelBuffer: CVPixelBuffer?
    
    let params:  [String : Any?];
    let cameraSelector: ViewCameraSelector?
    
    private var bridges: [CameraBridge] = []
    
    public var textureId: Int64?;
    private var frameAvailableCallback: ((Int64) -> Void)?
    
    private var lastAction: ActionCall?;
    
    private var isInitied = false;
    
    func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        guard let buffer = latestPixelBuffer else { return nil }
        return Unmanaged.passRetained(buffer)
    }
    
    required init(params: [String: Any?], frameAvailableCallback: ((Int64) -> Void)? = nil) {
        self.params = params
        self.frameAvailableCallback = frameAvailableCallback;
        let map = params["cameraSelector"] as? [String : Any?];
        cameraSelector = ViewCameraSelector.fromMap(map!);
    }
    
    func getCameraId() -> String? {
        return cameraSelector?.id;
    }
    
    func run() -> Void {
        if(textureId != nil) {
            run(textureId: textureId!);
        }
    }
    
    func run(textureId: Int64) -> Void {
        self.textureId = textureId;
        let status = AVCaptureDevice.authorizationStatus(for: .video);
        if(status == .authorized && !isInitied) {
            DispatchQueue.main.async {
                self.exec()
            }
            isInitied = true;
        } else {
            onUnauthorized();
            isInitied = false;
        }
    }
    
    func dispose() -> Void {};
    
    
    func getFps() -> Int {
        if(params["fps"] != nil) {
            return params["fps"] as! Int;
        }
        return  5;
    }
    
    
    func frameAvailable() -> Void {
        if(frameAvailableCallback != nil) {
            frameAvailableCallback!(textureId!);
        }
    }
    
    func onConnected(data: [String: Any?]) -> Void {
        var newData = data;
        newData["textureId"] = textureId;
        for bridge in bridges {
            bridge.onConnected(data: newData)
        }
       
        lastAction = BaseCamera.ActionCall(method: "onConnected", data: newData);
    }
    
    func onDisconnected() -> Void {
        for bridge in bridges {
            bridge.onDisconnected()
        }
        
        lastAction = BaseCamera.ActionCall(method: "onDisconnected");
    }
    
    func onUnauthorized() -> Void {
        for bridge in bridges {
            bridge.onUnauthorized()
        }
        lastAction = BaseCamera.ActionCall(method: "onUnauthorized");
    }
    
    func onFailed(message: String) -> Void {
        for bridge in bridges {
            bridge.onFailed(message: message)
        }
        lastAction = BaseCamera.ActionCall(method: "onFailed", data: message);
    }
    
    func onVideoFrameReceived(imageData: [String: Any]) -> Void {
        for bridge in bridges {
            bridge.onVideoFrameReceived(imageData: imageData)
        }
    }
    
    
    func getBridgeByViewId(_ viewId: Int) -> CameraBridge? {
        return bridges.first { $0.viewId == viewId }
    }
    
    func containsBridgeByViewId(_ viewId: Int) -> Bool {
        return getBridgeByViewId(viewId) != nil
    }
    
    func existsBridge() -> Bool {
        return !bridges.isEmpty
    }
    
    func removeBridge(_ bridge: CameraBridge) {
        if let index = bridges.firstIndex(where: { $0 === bridge }) {
            bridges.remove(at: index)
        }
    }
    
    func addBridge(_ bridge: CameraBridge) {
        let runCall = !bridges.isEmpty;
        let filter = bridges.filter { $0.viewId == bridge.viewId }
        if filter.isEmpty {
            bridges.append(bridge)
        }
        
        if(runCall && lastAction != nil) {
            switch lastAction!.method {
            case "onDisconnected":
                bridge.onDisconnected()
                break;
            case "onUnauthorized":
                bridge.onUnauthorized()
                break;
            case "onFailed":
                bridge.onFailed(message: (lastAction!.data as? String)!)
                break;
            default:
                bridge.onConnected(data: (lastAction!.data as? [String: Any])!)
                break;
            }
        }
        
    }
    
    internal func exec() -> Void {
        fatalError("Unimplemented method exec()!")
    };
    
    class ActionCall {
        let method: String;
        let data: Any?;
        
        init(method: String, data: Any? = nil) {
            self.method = method
            self.data = data
        }
        
    }
}

