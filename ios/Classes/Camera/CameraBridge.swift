//
//  CameraBridge.swift
//  Pods
//
//  Created by Michael Lopes on 28/09/25.
//

class CameraBridge {
    let viewId: Int;
    
    private var isConnectedCalled = false;
    
    init(viewId: Int) {
        self.viewId = viewId;
    }
    
    func onConnected(data: [String: Any?]) -> Void {
        isConnectedCalled = true;
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
    
    func onVideoFrameReceived(imageData: [String: Any]) -> Void {
        if(isConnectedCalled) {
            FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", imageData)
        }
    }
}
