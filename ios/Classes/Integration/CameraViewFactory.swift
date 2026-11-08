//
//  CameraViewFactory.swift
//  Pods
//
//  Created by Michael Lopes on 28/09/25.
//
import Flutter

/// Main thread only (method-channel handlers).
class CameraViewFactory {
    
    
    static let shared = CameraViewFactory();
    
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
    
    func getCameraById(id: String) -> BaseCamera? {
        return cameras.first { $0.getCameraId() == id }
    }
    
    func createView(args: [String: Any?]) -> Int64? {
        
        guard let registry = textureRegistry,
              let cameraSelectorMap = args["cameraSelector"] as? [String: Any],
              let viewId = args["viewId"] as? Int,
              let cameraSelector = ViewCameraSelector.fromMap(cameraSelectorMap) else {
            return nil
        }
        
        if let existingCamera = cameras.first(where: { $0.getCameraId() == cameraSelector.id }) {
            existingCamera.addBridge(CameraBridge(viewId: viewId))
            return existingCamera.textureId
        }

        let camera = createCamera(args: args)
        camera.addBridge(CameraBridge(viewId: viewId))
        let textureId = registry.register(camera)
        cameras.append(camera)
        camera.run(textureId: textureId);
        return textureId
    }
    
    func disposeView(args: [String: Any?]) {
        guard let viewId = args["viewId"] as? Int else { return }

        if let camera = cameras.first(where: { $0.containsBridgeByViewId(viewId) }) {
            if let bridge = camera.getBridgeByViewId(viewId) {
                camera.removeBridge(bridge)
            }

            if !camera.existsBridge() {
                cameras.removeAll { $0 === camera }
                camera.dispose()
                if let id = camera.textureId {
                    textureRegistry?.unregisterTexture(id)
                }
            }
        }
    }

    func disposeAll() {
        let all = cameras
        cameras.removeAll()
        for camera in all {
            camera.dispose()
            if let id = camera.textureId {
                textureRegistry?.unregisterTexture(id)
            }
        }
    }
    
    func createCamera(args: [String: Any?]) -> BaseCamera {
        return DeviceCamera(params: args, frameAvailableCallback: { [weak self] textureId in
            self?.textureRegistry?.textureFrameAvailable(textureId)
        })
    }
    
}
