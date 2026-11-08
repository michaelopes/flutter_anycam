//
//  FlutterEventChannel.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import Flutter

class FlutterEventStreamChannel: NSObject, FlutterStreamHandler  {
    
    /// Main thread only.
    private var eventSink: FlutterEventSink?
    static let shared = FlutterEventStreamChannel();
    
    private override init() {}
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
    
    func send(_ viewId: Int, _ method: String, _ data: [String: Any?]) {
        send(viewId, method, data, completion: nil)
    }

    /// `completion` runs exactly once on the main thread; `delivered` is false when no
    /// listener received the event (its payload, e.g. a native TF frame, is then orphaned).
    func send(_ viewId: Int, _ method: String, _ data: [String: Any?], completion: ((Bool) -> Void)?) {
        let result = [
            "viewId" : viewId,
            "method" : method,
            "data": data
        ] as [String : Any]
        
        DispatchQueue.main.async {
            guard let sink = self.eventSink else {
                completion?(false)
                return
            }
            sink(result)
            completion?(true)
        }
    }
    
}
