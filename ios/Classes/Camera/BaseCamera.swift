//
//  BaseCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import AVFoundation

class BaseCamera: UIView {
    
    static var waitPermission = false;
    
    var viewId: Int
    var params:  [String : Any?] = [String: Any?]();
    var cameraSelector: ViewCameraSelector?
    
    init(frame: CGRect, viewId: Int, params: [String: Any?]) {
        self.viewId = viewId;
        self.params = params
        let map = params["cameraSelector"] as? [String : Any?];
        cameraSelector = ViewCameraSelector.fromMap(map!);
        super.init(frame: frame)
    }
    
    required convenience init?(coder: NSCoder, viewId: Int, params: [String: Any?]) {
        self.init(coder: coder)
        self.viewId = viewId;
        self.params = params
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented. Use init(coder:viewId:params:) instead.")
    }
    
    
    func run() -> Void {
        let status = AVCaptureDevice.authorizationStatus(for: .video);
        if(status == .authorized) {
            DispatchQueue.main.async {
                self.exec()
            }
        } else {
            FlutterEventStreamChannel.shared.send(self.viewId, "onUnauthorized", [String : Any?]());
        }
    }
    
    func dispose() -> Void {};
    
    
    func getFps() -> Int {
        if(params["fps"] != nil) {
            return params["fps"] as! Int;
        }
        return  5;
    }
    
    
    internal func exec() -> Void {
        fatalError("Unimplemented method exec()!")
    };
}

