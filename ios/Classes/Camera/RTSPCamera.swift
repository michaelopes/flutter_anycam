//
//  RTSPCamera.swift
//  Pods
//
//  Created by Michael Lopes on 09/08/25.
//
//import HaishinKit

class RTSPCamera : BaseCamera {
    
    
    public override init(frame: CGRect, viewId: Int, params: [String : Any?]) {
        super.init(frame: frame, viewId: viewId, params: params);
    }
    
    @MainActor required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    required convenience init?(coder: NSCoder, viewId: Int, params: [String : Any?]) {
        fatalError("init(coder:viewId:params:) has not been implemented")
    }
    
    
    override func exec() -> Void {
        //let rtspString = "rtsp://admin:1@192.168.18.93:554/mode=real&idc=1&ids=1"
        fatalError("RTSPCamera has not been implemented yet")
    }
    
    
    override func layoutSubviews() {
        super.layoutSubviews()
        
    }
}


