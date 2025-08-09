//
//  ViewCameraSelector.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import Foundation

class ViewCameraSelector {
    let id: String
    let name: String
    let lensFacing: String
    let sensorOrientation: Int
    var cameraSelectorRTSP: ViewCameraSelectorRTSP?
    
    init(id: String, name: String, lensFacing: String, sensorOrientation: Int) {
        self.id = id
        self.name = name
        self.lensFacing = lensFacing
        self.sensorOrientation = sensorOrientation
    }
    
    static func fromMap(_ map: [String: Any?]) -> ViewCameraSelector? {
        guard let id = map["id"] as? String,
              let name = map["name"] as? String,
              let lensFacing = map["lensFacing"] as? String,
              let sensorOrientation = map["sensorOrientation"] as? Int else {
            return nil
        }
        
        let cameraSelector = ViewCameraSelector(
            id: id,
            name: name,
            lensFacing: lensFacing,
            sensorOrientation: sensorOrientation
        )
        
        if let url = map["url"] as? String,
           let username = map["username"] as? String,
           let password = map["password"] as? String {
            cameraSelector.cameraSelectorRTSP = ViewCameraSelectorRTSP(
                url: url,
                username: username,
                password: password
            )
        }
        
        return cameraSelector
    }
    
    func toMap() -> [String: Any?] {
        var map: [String: Any?] = [
            "id": id,
            "name": name,
            "lensFacing": lensFacing,
            "sensorOrientation": sensorOrientation
        ]
        
        // Optionally add RTSP data if available
        if let rtsp = cameraSelectorRTSP {
            map["url"] = rtsp.url
            map["username"] = rtsp.username
            map["password"] = rtsp.password
        }
        
        return map
    }
    
    class ViewCameraSelectorRTSP {
        let url: String
        let username: String
        let password: String
        
        init(url: String, username: String, password: String) {
            self.url = url
            self.username = username
            self.password = password
        }
    }
}
