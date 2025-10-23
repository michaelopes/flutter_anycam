//
//  DeviceCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import AVFoundation
import Flutter

enum CameraSensorOrientation: Int {
    case rotation0   = 0
    case rotation90  = 90
    case rotation180 = 180
    case rotation270 = 270
}

class DeviceCamera : BaseCamera {
    
    private let captureSession = AVCaptureUtil.shared.get();
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var videoInput: AVCaptureDeviceInput?
    private var isCapturing = false
    
    
    private lazy var limiter: FrameRateLimiterUtil<CMSampleBuffer> = {
        return FrameRateLimiterUtil<CMSampleBuffer>(targetFps: self.getFps()) { [weak self] sampleBuffer in
            self?.processBuffer(sampleBuffer: sampleBuffer)
        } onFrameSkipped: { sampleBuffer in
            // Implementação opcional para frames pulados
        }
    }()

    override func exec() -> Void {
        
        guard let input = AVCaptureUtil.shared.getCameraInput(cameraSelector: self.cameraSelector!) else {
            onFailed(message: "Error: It is not possible to open two instances of the same camera.")
            return;
        }
        
        videoInput = input;
        videoOutput = AVCaptureVideoDataOutput()
        videoOutput?.setSampleBufferDelegate(self, queue: DispatchQueue.main)
        videoOutput?.alwaysDiscardsLateVideoFrames = true
        videoOutput?.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        
        
        captureSession.beginConfiguration()
        
        captureSession.addInput(input);
        
        
        let sensorRotation = getFinalRotation(for: input.device.position);
        
        if let output = videoOutput, captureSession.canAddOutput(output) {
            captureSession.addOutput(output)
        }
        
        var correntOrientation: AVCaptureVideoOrientation?;
        for connection in videoOutput!.connections {
            correntOrientation = getVideoOrientation();
            connection.videoOrientation = correntOrientation!;
            if input.device.position == .front && connection.isVideoMirroringSupported {
                connection.isVideoMirrored = true
            }
            
        }
        
        
        captureSession.commitConfiguration()
        
        captureSession.startRunning()
        
        isCapturing = true;
        
        print("🧩 Starting camera setup")
        print("🧩 Input: \(input.device.localizedName)")
        print("🧩 Can add output: \(captureSession.canAddOutput(videoOutput!))")
        print("🧩 Delegate: \(videoOutput?.sampleBufferDelegate != nil)")
        print("🧩 Before startRunning: \(captureSession.isRunning)")
        
        let format =  input.device.activeFormat as AVCaptureDevice.Format?
        if(format != nil && correntOrientation != nil) {
            let dimensions = CMVideoFormatDescriptionGetDimensions(format!.formatDescription)
            
            var width = dimensions.width;
            var height = dimensions.height;
            
            if(sensorRotation == 90 || sensorRotation == 270) {
                let temp = width;
                width = height;
                height = temp;
            }
            
            onConnected(data: [
                "width": width,
                "height": height
            ]);
        }
    }
    
    
    func getFinalRotation(for position: AVCaptureDevice.Position) -> Int {
        let sensor = getSensorOrientation(for: position)
        let device = getDeviceRotation()
        return (sensor + device) % 360
    }
    
    func getSensorOrientation(for position: AVCaptureDevice.Position) -> Int {
        switch position {
        case .back:
            return 90
        case .front:
            return 270
        default:
            return 90
        }
    }
    
    func getDeviceRotation() -> Int {
        switch UIDevice.current.orientation {
        case .portrait:
            return 0
        case .landscapeLeft:
            return 90
        case .portraitUpsideDown:
            return 180
        case .landscapeRight:
            return 270
        default:
            return 0
        }
    }
    
    private func getVideoOrientation() -> AVCaptureVideoOrientation {
        let orientation = UIDevice.current.orientation
        switch orientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return  .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return .portrait
        }
    }
    
    override func dispose() -> Void {
        if(cameraSelector != nil && videoInput != nil) {
            AVCaptureUtil.shared.resetCameraInput(cameraSelector: cameraSelector!);
            if(videoOutput != nil){
                captureSession.removeOutput(videoOutput!)
            }
            
            if isCapturing {
                if(captureSession.inputs.isEmpty) {
                    captureSession.stopRunning()
                    isCapturing = false
                }
            }
        }
        
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        videoOutput = nil
    }
    
}

extension DeviceCamera: AVCaptureVideoDataOutputSampleBufferDelegate {
    
    
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isCapturing else { return }
        guard CMSampleBufferDataIsReady(sampleBuffer) else {
            return
        }
        if let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            latestPixelBuffer = buffer
            frameAvailable();
        }
        limiter.processFrame(sampleBuffer)
        
    }
    
    private func processBuffer(sampleBuffer: CMSampleBuffer) {
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else {
            return
        }
        
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            return
        }
        
        let bytesPerPixel = 4
        let requiredBytesPerRow = width * bytesPerPixel
        
        var imageData: Data
        if bytesPerRow == requiredBytesPerRow {
            imageData = Data(bytes: baseAddress, count: height * bytesPerRow)
        } else {
            imageData = Data(count: height * requiredBytesPerRow)
            imageData.withUnsafeMutableBytes { destPtr in
                let src = baseAddress.assumingMemoryBound(to: UInt8.self)
                let dest = destPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                
                for row in 0..<height {
                    let srcRow = src + row * bytesPerRow
                    let destRow = dest + row * requiredBytesPerRow
                    memcpy(destRow, srcRow, requiredBytesPerRow)
                }
            }
        }
        
        let imageBuffer: [String: Any] = [
            "width": width,
            "height": height,
            "rotation": 0,
            "format": "BGRA8888",
            "planes": [[
                "rowStride": requiredBytesPerRow,
                "pixelStride": bytesPerPixel,
                "width": width,
                "height": height,
                "bytes": FlutterStandardTypedData(bytes: imageData)
            ]],
            "bytes": FlutterStandardTypedData(bytes: imageData)
        ]
        
        onVideoFrameReceived(imageData: imageBuffer);
        
    }
}
