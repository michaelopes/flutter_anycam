//
//  DeviceCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import AVFoundation
import Flutter

class DeviceCamera : BaseCamera {
    
    private let captureSession = AVCaptureUtil.shared.get();
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var videoInput: AVCaptureDeviceInput?
    private var isCapturing = false
    
    
    public override init(frame: CGRect, viewId: Int, params: [String : Any?]) {
        super.init(frame: frame, viewId: viewId, params: params);
    }
    
    @MainActor required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    required convenience init?(coder: NSCoder, viewId: Int, params: [String : Any?]) {
        fatalError("init(coder:viewId:params:) has not been implemented")
    }
    
    
    
    private lazy var limiter: FrameRateLimiterUtil<CMSampleBuffer> = {
        return FrameRateLimiterUtil<CMSampleBuffer>(targetFps: self.getFps()) { [weak self] sampleBuffer in
            self?.processBuffer(sampleBuffer: sampleBuffer)
        } onFrameSkipped: { sampleBuffer in
            // Implementação opcional para frames pulados
        }
    }()
    
    
    override func exec() -> Void {
        
        guard let input = AVCaptureUtil.shared.getCameraInput(cameraSelector: self.cameraSelector!) else {
            FlutterEventStreamChannel.shared.send(viewId, "onFailed", [
                "message": "Error: It is not possible to open two instances of the same camera."
            ])
            return;
        }
        
        videoInput = input;
        videoOutput = AVCaptureVideoDataOutput()
        videoOutput?.setSampleBufferDelegate(self, queue: DispatchQueue(label: "cameraFrameQueue"))
        videoOutput?.alwaysDiscardsLateVideoFrames = true
        videoOutput?.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        
        
        captureSession.beginConfiguration()
        captureSession.addInput(input);
        
        
        if let output = videoOutput, captureSession.canAddOutput(output) {
            captureSession.addOutput(output)
        }
        
        
        for connection in videoOutput!.connections {
            connection.videoOrientation = getVideoOrientation(forDegrees: 0);
            if cameraSelector?.lensFacing == "front" && connection.isVideoMirroringSupported {
                connection.isVideoMirrored = true
            }
        }
        
        
        captureSession.commitConfiguration()
        
        
        previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        previewLayer?.videoGravity = .resizeAspectFill
        previewLayer?.frame = bounds
        
        if let layer = previewLayer {
            self.layer.addSublayer(layer)
        }
        
        captureSession.startRunning()
        isCapturing = true;
        
        let width = Int(previewLayer!.bounds.width)
        let height = Int(previewLayer!.bounds.height)
        
        FlutterEventStreamChannel.shared.send(viewId, "onConnected", [
            "width": width,
            "height":  height,
            "isPortrait": height > width,
            "rotation": 0
        ]);
        
    }
    
    private func getVideoOrientation(forDegrees degrees: Int) -> AVCaptureVideoOrientation {
        switch degrees {
        case 0:
            return .portrait
        case 180:
            return .portraitUpsideDown
        case 90:
            return .landscapeLeft
        case -90:
            return .landscapeRight
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
    
    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
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
        
        FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", imageBuffer)
        
    }

}
