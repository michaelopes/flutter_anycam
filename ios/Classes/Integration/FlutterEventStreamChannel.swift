//
//  FlutterEventChannel.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import Flutter

class FlutterEventStreamChannel: NSObject, FlutterStreamHandler  {
    
    var eventSink: FlutterEventSink?
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
    
    
    func send(_ viewId: Int,_ method: String,_ data: [String : Any?] ) -> Void {
        let result = [
            "viewId" : viewId,
            "method" : method,
            "data": data
        ] as [String : Any]
        
        
        DispatchQueue.main.async {
            self.eventSink?(result)
        }
        
    }
    
    
}
