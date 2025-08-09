//
//  CameraFactory.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
class CameraFactory {
    //as [String: Any?] ?? [String: Any?]()
    let frame: CGRect;
    var params: [String : Any?] = [String: Any?]();
    
    init(frame: CGRect) {
       self.frame = frame
    }
    
    func getCamera(vId: Int, args: Any?) -> BaseCamera{
    
        self.params = (args as? [String: Any?]) ?? [String: Any?]();
        let map = params["cameraSelector"] as? [String : Any?];
        let cameraSelector = ViewCameraSelector.fromMap(map!);
                
        switch cameraSelector?.lensFacing {
        case "rtsp": 
            return RTSPCamera(frame: self.frame, viewId: vId, params: self.params)
        case "back":
            return DeviceCamera(frame: self.frame, viewId: vId, params: self.params)
        case "front":
            return DeviceCamera(frame: self.frame, viewId: vId, params: self.params)
        default:
            return DeviceCamera(frame: self.frame, viewId: vId, params: self.params)
        }
    }
}
