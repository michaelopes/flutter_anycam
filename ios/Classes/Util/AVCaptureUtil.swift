//
//  AVCaptureUtil.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import AVFoundation

class AVCaptureUtil {
    private var captureSession: AVCaptureMultiCamSession?
    static let shared = AVCaptureUtil();
    
    var backCamInput: AVCaptureDeviceInput?;
    var frontCamInput: AVCaptureDeviceInput?;
    
    private init() {}
    
    private func check() {
        if(captureSession == nil) {
            captureSession = AVCaptureMultiCamSession();
        }
    }
    
    
    public func get() ->  AVCaptureMultiCamSession{
        check();
        return captureSession!
    }
    
    func resetCameraInput(cameraSelector: ViewCameraSelector) -> Void {
        if(cameraSelector.lensFacing == "back" && backCamInput != nil) {
            captureSession?.removeInput(backCamInput!)
            backCamInput = nil;
        } else if(cameraSelector.lensFacing == "front" && frontCamInput != nil) {
            captureSession?.removeInput(frontCamInput!)
            frontCamInput = nil;
        }
    }
    
    func setFlash(_ value: Bool) -> Void {
        if(backCamInput != nil) {
            let device = backCamInput!.device;
            if device.hasTorch {
                do {
                    try device.lockForConfiguration()
                    device.torchMode = value ? .on : .off
                    if(value) {
                       try device.setTorchModeOn(level: 1);
                    }
                    device.unlockForConfiguration()
                } catch {
                    print("Torch could not be used")
                }
            }
        }
    }
    
    func getCameraInput(cameraSelector: ViewCameraSelector) -> AVCaptureDeviceInput?  {
        if(cameraSelector.lensFacing == "back" && backCamInput == nil) {
            
            let backCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
            guard let backCamInput = try? AVCaptureDeviceInput(device: backCam!) else {
                return nil
            }
            self.backCamInput = backCamInput
            
            return backCamInput
            
            
        } else if(cameraSelector.lensFacing == "front" && frontCamInput == nil) {
            
            let frontCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position:  .front)
            guard let frontCamInput = try? AVCaptureDeviceInput(device: frontCam!) else {
                return nil
            }
            self.frontCamInput = frontCamInput
            return frontCamInput;
            
        }
        
        return nil;
    }
    
}
