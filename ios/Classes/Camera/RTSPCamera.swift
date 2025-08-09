//
//  RTSPCamera.swift
//  Pods
//
//  Created by Michael Lopes on 09/08/25.
//
import HaishinKit
import AVFoundation


class RTSPCamera : BaseCamera {
    
    private var playerView: PiPHKView?
    private var rtmpStream: RTMPStream?
    let rtmpConnection = RTMPConnection()
    
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
        
        playerView = PiPHKView(frame: bounds);
        playerView?.videoGravity = .resizeAspectFill
        
        rtmpStream = RTMPStream(connection: rtmpConnection)
        rtmpStream?.addOutput(playerView!)
        
        Task {
            await setup()
        }
    }
    
    private func setup() async {
        do {
            let rtspString = "rtsp://admin:1@192.168.18.93:554/mode=real&idc=1&ids=1"
           
            try await rtmpConnection.connect(rtspString)
            try await rtmpStream?.play(rtspString)
        } catch {
            print("Error \(error)")
        }
    }
    
    
    override func layoutSubviews() {
        super.layoutSubviews()
        playerView?.frame = bounds;
        
    }
}
