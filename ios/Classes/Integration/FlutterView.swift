//
//  FlutterView.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import Flutter
import UIKit

class FlutterView: NSObject, FlutterPlatformView {
    private let cameraView: BaseCamera
    
    init(frame: CGRect, viewId: Int64, args: Any?) {
        let factory = CameraFactory(frame: frame);
        cameraView = factory.getCamera(vId: Int(viewId), args: args)
        self.cameraView.run()   
        super.init()
      
    }
    
    
    func view() -> UIView {
        return cameraView
    }
    
    deinit {
        cameraView.dispose();
    }
    
}
