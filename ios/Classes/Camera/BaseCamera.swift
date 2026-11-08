//
//  BaseCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import AVFoundation
import Flutter

struct FpSize {
    let width: Int
    let height: Int
}

class BaseCamera: NSObject, FlutterTexture {
    
    static var waitPermission = false;

    /// Written on the capture queue, read by Flutter's raster thread in `copyPixelBuffer`.
    private let pixelBufferLock = NSLock()
    private var _latestPixelBuffer: CVPixelBuffer?
    var latestPixelBuffer: CVPixelBuffer? {
        get {
            pixelBufferLock.lock()
            defer { pixelBufferLock.unlock() }
            return _latestPixelBuffer
        }
        set {
            pixelBufferLock.lock()
            _latestPixelBuffer = newValue
            pixelBufferLock.unlock()
        }
    }
    
    let params:  [String : Any?];
    let cameraSelector: ViewCameraSelector?
    
    /// Mutated on main, iterated from the capture queue.
    private let bridgesLock = NSLock()
    private var bridges: [CameraBridge] = []
    
    public var textureId: Int64?;
    private var frameAvailableCallback: ((Int64) -> Void)?
    
    private var lastAction: ActionCall?;
    
    private var isInitied = false;
    private let stateLock = NSLock()
    private var _isDisposed = false
    var isDisposed: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return _isDisposed
    }

    /// Frames handed to the main queue but not yet given to the Flutter sink. One at a time:
    /// a busy main thread must drop frames, not queue them (each one is a full-size copy).
    private var framesInFlight = 0
    
    var preferredSize : FpSize?;
    var resizeFrame : FpSize?;
    var filter : Int = 0;
    /// Quando `type` do createView é `tf`, os frames são registados nativamente para TFLite (espelha Android).
    let isTfMode: Bool;
    
    func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        guard let buffer = latestPixelBuffer else { return nil }
        return Unmanaged.passRetained(buffer)
    }
    
    required init(params: [String: Any?], frameAvailableCallback: ((Int64) -> Void)? = nil) {
        self.params = params
        self.frameAvailableCallback = frameAvailableCallback;
        let map = params["cameraSelector"] as? [String : Any?];
        cameraSelector = map != nil ? ViewCameraSelector.fromMap(map!) : nil;
        
        if let preferredSizeMap = params["preferredSize"] as? [String: Any],
              let width = preferredSizeMap["width"] as? Int,
              let height = preferredSizeMap["height"] as? Int {
             preferredSize = FpSize(width: width, height: height)
        }
        
        if let resizeFrameMap = params["resizeFrame"] as? [String: Any],
              let width = resizeFrameMap["width"] as? Int,
              let height = resizeFrameMap["height"] as? Int {
            resizeFrame = FpSize(width: width, height: height)
        }
        
        filter = params["filter"] as? Int ?? 0;
        isTfMode = (params["type"] as? String) == "tf"
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
        if isDisposed { return }
        let status = AVCaptureDevice.authorizationStatus(for: .video);
        if status == .authorized {
            // Re-running an initialized camera (e.g. broadcastPermissionGranted) is a no-op.
            if !isInitied {
                isInitied = true;
                DispatchQueue.main.async {
                    if self.isDisposed { return }
                    self.exec()
                }
            }
        } else {
            onUnauthorized();
            isInitied = false;
        }
    }
    
    /// Subclasses must call super.
    func dispose() -> Void {
        stateLock.lock()
        _isDisposed = true
        stateLock.unlock()
        latestPixelBuffer = nil
    };
    
    func getFps() -> Int {
        if let fps = params["fps"] as? Int, fps > 0 {
            return fps
        }
        return 5;
    }
    
    func frameAvailable() -> Void {
        if let id = textureId, let callback = frameAvailableCallback, !isDisposed {
            callback(id)
        }
    }

    private func snapshotBridges() -> [CameraBridge] {
        bridgesLock.lock()
        defer { bridgesLock.unlock() }
        return bridges
    }
    
    func onConnected(data: [String: Any?]) -> Void {
        var newData = data;
        newData["textureId"] = textureId;
        for bridge in snapshotBridges() {
            bridge.onConnected(data: newData)
        }
       
        lastAction = BaseCamera.ActionCall(method: "onConnected", data: newData);
    }
    
    func onDisconnected() -> Void {
        for bridge in snapshotBridges() {
            bridge.onDisconnected()
        }
        
        lastAction = BaseCamera.ActionCall(method: "onDisconnected");
    }
    
    func onUnauthorized() -> Void {
        for bridge in snapshotBridges() {
            bridge.onUnauthorized()
        }
        lastAction = BaseCamera.ActionCall(method: "onUnauthorized");
    }
    
    func onFailed(message: String) -> Void {
        for bridge in snapshotBridges() {
            bridge.onFailed(message: message)
        }
        lastAction = BaseCamera.ActionCall(method: "onFailed", data: message);
    }

    /// True when a new frame may be produced: nothing is still waiting for the main thread.
    func canDeliverFrame() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return framesInFlight == 0 && !_isDisposed
    }
    
    /// Native TF frames that no Dart listener received are closed here; otherwise they would
    /// stay registered forever.
    ///
    /// A TF frame has a single owner, so it goes only to the most recently attached connected
    /// view (the top route when two screens share a camera); otherwise one view's close() would
    /// free the frame the other is still running inference on.
    func onVideoFrameReceived(imageData: [String: Any]) -> Void {
        let tfFrameId = isTfMode ? imageData["id"] as? String : nil
        let targets = tfFrameId != nil ? snapshotBridges().reversed() : snapshotBridges()

        stateLock.lock()
        framesInFlight += 1
        stateLock.unlock()

        var pending = 0
        var anyDelivered = false
        let completionLock = NSLock()
        var sent = 0
        let finish: (Bool) -> Void = { [weak self] delivered in
            completionLock.lock()
            if delivered { anyDelivered = true }
            pending -= 1
            let done = pending == 0
            let delivered = anyDelivered
            completionLock.unlock()
            guard done else { return }
            if let camera = self {
                camera.frameFinished(tfFrameId: tfFrameId, delivered: delivered)
            } else if !delivered, let id = tfFrameId {
                TfFrameHandler.shared.closeFrame(id)
            }
        }

        completionLock.lock()
        for bridge in targets {
            pending += 1
            if bridge.onVideoFrameReceived(imageData: imageData, completion: finish) {
                sent += 1
                if tfFrameId != nil { break }
            } else {
                pending -= 1
            }
        }
        let nothingSent = sent == 0
        completionLock.unlock()

        if nothingSent {
            frameFinished(tfFrameId: tfFrameId, delivered: false)
        }
    }

    private func frameFinished(tfFrameId: String?, delivered: Bool) {
        stateLock.lock()
        framesInFlight = max(0, framesInFlight - 1)
        stateLock.unlock()
        if !delivered, let id = tfFrameId {
            TfFrameHandler.shared.closeFrame(id)
        }
    }
    
    
    func getBridgeByViewId(_ viewId: Int) -> CameraBridge? {
        bridgesLock.lock()
        defer { bridgesLock.unlock() }
        return bridges.first { $0.viewId == viewId }
    }
    
    func containsBridgeByViewId(_ viewId: Int) -> Bool {
        return getBridgeByViewId(viewId) != nil
    }
    
    func existsBridge() -> Bool {
        bridgesLock.lock()
        defer { bridgesLock.unlock() }
        return !bridges.isEmpty
    }
    
    func removeBridge(_ bridge: CameraBridge) {
        bridgesLock.lock()
        if let index = bridges.firstIndex(where: { $0 === bridge }) {
            bridges.remove(at: index)
        }
        bridgesLock.unlock()
    }
    
    func setZoom(zoom: Float) -> Void { }

    func setExposureCompensation(value: Int) -> Void { }
    
    func addBridge(_ bridge: CameraBridge) {
        bridgesLock.lock()
        let runCall = !bridges.isEmpty;
        if !bridges.contains(where: { $0.viewId == bridge.viewId }) {
            bridges.append(bridge)
        }
        bridgesLock.unlock()
        
        if runCall, let action = lastAction {
            switch action.method {
            case "onDisconnected":
                bridge.onDisconnected()
            case "onUnauthorized":
                bridge.onUnauthorized()
            case "onFailed":
                bridge.onFailed(message: (action.data as? String) ?? "")
            default:
                if let data = action.data as? [String: Any?] {
                    bridge.onConnected(data: data)
                }
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
