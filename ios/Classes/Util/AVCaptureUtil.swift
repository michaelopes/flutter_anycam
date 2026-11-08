//
//  AVCaptureUtil.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import AVFoundation

class AVCaptureUtil {
    private var captureSession: AVCaptureSession?
    static let shared = AVCaptureUtil();

    /// Serializes the blocking startRunning/stopRunning calls of the shared session, so a
    /// stop issued by a dispose can never overtake the start of the next camera.
    let sessionQueue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.session")
    
    var backCamInput: AVCaptureDeviceInput?;
    var frontCamInput: AVCaptureDeviceInput?;

    // Main thread only (camera exec/dispose).
    private weak var backOwner: DeviceCamera?
    private weak var frontOwner: DeviceCamera?

    private final class WeakCamera {
        weak var camera: DeviceCamera?
        init(_ camera: DeviceCamera) { self.camera = camera }
    }

    /// Cameras whose device was taken by a newer camera on single-camera hardware; the most
    /// recent one reopens when the device is free again (e.g. the covering route is popped).
    private var suspended: [WeakCamera] = []
    
    private init() {}
    
    private func check() {
        if(captureSession == nil) {
            if(isMultiCamAvailable()) {
                captureSession = AVCaptureMultiCamSession();
            } else {
                captureSession = AVCaptureSession();
            }
        }
    }
    
    
    
    func isMultiCamAvailable() -> Bool {
        if #available(iOS 13.0, *) {
            return AVCaptureMultiCamSession.isMultiCamSupported
        } else {
            return false
        }
    }
    
    
    public func get() ->  AVCaptureSession{
        check();
        return captureSession!
    }
    
    func removeByType(_ type: String) {
        if(type == "back" && backCamInput != nil) {
            captureSession?.removeInput(backCamInput!)
            backCamInput = nil;
        } else if(type == "front" && frontCamInput != nil) {
            captureSession?.removeInput(frontCamInput!)
            frontCamInput = nil;
        }
    }
    
    func resetCameraInput(cameraSelector: ViewCameraSelector) -> Void {
        if(cameraSelector.lensFacing == "back" && backCamInput != nil) {
            removeByType("back");
        } else if(cameraSelector.lensFacing == "front" && frontCamInput != nil) {
            removeByType("front");
        }
    }
    
    /// Removes `input` only if it is still the registered one: an older camera on the same
    /// lens must never remove the input of the camera that replaced it.
    func release(input: AVCaptureDeviceInput) {
        if backCamInput === input {
            backCamInput = nil
            backOwner = nil
        }
        if frontCamInput === input {
            frontCamInput = nil
            frontOwner = nil
        }
        if let session = captureSession, session.inputs.contains(where: { $0 === input }) {
            session.removeInput(input)
        }
    }

    /// Main thread. Called once a camera stopped using the device for good (disposed/failed).
    func cameraDidClose(_ camera: DeviceCamera) {
        suspended.removeAll { $0.camera == nil || $0.camera === camera }
        guard !isMultiCamAvailable(), let session = captureSession, session.inputs.isEmpty else {
            return
        }
        while let last = suspended.popLast() {
            if let cam = last.camera, !cam.isDisposed {
                DispatchQueue.main.async { cam.resumeFromSuspension() }
                return
            }
        }
    }

    private func suspend(_ victim: DeviceCamera?, for owner: DeviceCamera) {
        guard let victim = victim, victim !== owner else { return }
        suspended.removeAll { $0.camera == nil || $0.camera === victim }
        suspended.append(WeakCamera(victim))
        victim.suspendForOtherCamera()
    }

    func setFlash(_ value: Bool) -> Void {
        guard let device = backCamInput?.device, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if value {
                try device.setTorchModeOn(level: 1)
            } else {
                device.torchMode = .off
            }
        } catch {
            print("Torch could not be used")
        }
    }
    
    /// Main thread.
    func getCameraInput(cameraSelector: ViewCameraSelector, owner: DeviceCamera) -> AVCaptureDeviceInput?  {
        
        if(!isMultiCamAvailable()) {
            // Single-camera hardware: the previous camera releases the device and is parked
            // (instead of freezing forever without any event).
            if(backCamInput != nil) {
               suspend(backOwner, for: owner)
               removeByType("back");
            } else if(frontCamInput != nil) {
               suspend(frontOwner, for: owner)
               removeByType("front");
            }
        }
        
        if(cameraSelector.lensFacing == "back" && backCamInput == nil) {
            guard let backCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: backCam) else {
                return nil
            }
            self.backCamInput = input
            self.backOwner = owner
            return input
            
        } else if(cameraSelector.lensFacing == "front" && frontCamInput == nil) {
            guard let frontCam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
                  let input = try? AVCaptureDeviceInput(device: frontCam) else {
                return nil
            }
            self.frontCamInput = input
            self.frontOwner = owner
            return input
        }
        
        return nil;
    }
    
}
