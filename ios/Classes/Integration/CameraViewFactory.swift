//
//  CameraViewFactory.swift
//  Pods
//
//  Created by Michael Lopes on 28/09/25.
//
import Flutter

class CameraViewFactory {
    
    
    static let shared = CameraViewFactory();
    
    private var cameraSelector: ViewCameraSelector?
    private var textureRegistry: FlutterTextureRegistry?
    
    private var cameras : [BaseCamera] = []
    
    private init() {}
    
    func load(textureRegistry: FlutterTextureRegistry) {
        self.textureRegistry = textureRegistry;
    }
    
    func broadcastPermissionGranted() {
        for camera in cameras {
            camera.run();
        }
    }
    
    func createView(args: [String: Any?]) -> Int64? {
        
        guard let cameraSelectorMap = args["cameraSelector"] as? [String: Any],
              let viewId = args["viewId"] as? Int else {
            return nil
        }
        
        cameraSelector = ViewCameraSelector.fromMap(cameraSelectorMap)
        
        let filter = cameras.filter { $0.getCameraId() == cameraSelector?.id }
        
        if let existingCamera = filter.first {
            let textureId = existingCamera.textureId
            existingCamera.addBridge(CameraBridge(viewId: viewId))
            return textureId
        } else {
            let camera = createCamera(args: args)
            camera.addBridge(CameraBridge(viewId: viewId))
            
            let textureId = textureRegistry!.register(camera)
            
            camera.run(textureId: textureId);
            
            cameras.append(camera)
            return camera.textureId
        }
    }
    
    func disposeView(args: [String: Any?]) {
        guard let viewId = args["viewId"] as? Int else { return }

        if let camera = cameras.first(where: { $0.containsBridgeByViewId(viewId) }) {
            if let bridge = camera.getBridgeByViewId(viewId) {
                camera.removeBridge(bridge)
            }

            if !camera.existsBridge() {
                camera.dispose()
                textureRegistry!.unregisterTexture(camera.textureId!)
                if let index = cameras.firstIndex(where: { $0 === camera }) {
                    cameras.remove(at: index)
                }
            }
        }
    }
    
    func createCamera(args: [String: Any?]) -> BaseCamera {
        return DeviceCamera(params: args, frameAvailableCallback: { textureId in
            self.textureRegistry!.textureFrameAvailable(textureId)
        })
    }
    
}
