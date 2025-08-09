//
//  FlutterViewFactory.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import Flutter;

class FlutterViewFactory: NSObject, FlutterPlatformViewFactory {
    
    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
    
    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        return FlutterView(frame: frame,  viewId: viewId, args: args)
    }
}
