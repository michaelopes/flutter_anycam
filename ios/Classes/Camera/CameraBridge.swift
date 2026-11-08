//
//  CameraBridge.swift
//  Pods
//
//  Created by Michael Lopes on 28/09/25.
//

class CameraBridge {
    let viewId: Int;
    
    private let lock = NSLock()
    private var isConnectedCalled = false;
    
    init(viewId: Int) {
        self.viewId = viewId;
    }
    
    func onConnected(data: [String: Any?]) -> Void {
        lock.lock()
        isConnectedCalled = true;
        lock.unlock()
        FlutterEventStreamChannel.shared.send(self.viewId, "onConnected", data);
    }
    
    func onDisconnected() -> Void {
        FlutterEventStreamChannel.shared.send(self.viewId, "onDisconnected", [String : Any?]());
    }
    
    func onUnauthorized() -> Void {
        FlutterEventStreamChannel.shared.send(self.viewId, "onUnauthorized", [String : Any?]());
    }
    
    func onFailed(message: String) -> Void {
        FlutterEventStreamChannel.shared.send(self.viewId, "onFailed",  [
            "message": message
        ])
    }
    
    /// Returns false when the frame was not sent (the bridge is not connected yet);
    /// otherwise `completion` reports the delivery on the main thread.
    func onVideoFrameReceived(imageData: [String: Any], completion: @escaping (Bool) -> Void) -> Bool {
        lock.lock()
        let connected = isConnectedCalled
        lock.unlock()
        guard connected else { return false }
        FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", imageData, completion: completion)
        return true
    }
}
