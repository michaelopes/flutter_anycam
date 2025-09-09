//
//  CameraUtil.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import AVFoundation

class CameraUtil {
    static func availableCameras() -> [[String: Any]] {
        return self.camerasList()
    }
    
    private static func camerasList() -> [[String: Any]] {
        return [
            [
                "id": "0",
                "name": "front camera",
                "lensFacing": "front",
                "sensorOrientation": 0,
            ],
            [
                "id": "1",
                "name": "back camera",
                "lensFacing": "back",
                "sensorOrientation": 0,
            ]
        ]
    }
}
