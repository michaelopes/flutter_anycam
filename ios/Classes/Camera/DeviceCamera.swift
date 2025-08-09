//
//  DeviceCamera.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//

import AVFoundation

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
    
    func convertToBGRA8888(sampleBuffer: CMSampleBuffer) -> (pixelBuffer: CVPixelBuffer?, formatDescription: CMFormatDescription?) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return (nil, nil)
        }
        
        // Bloqueia o buffer de base para acesso
        CVPixelBufferLockBaseAddress(imageBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(imageBuffer, .readOnly) }
        
        // Verifica se já está no formato BGRA8888
        let pixelFormat = CVPixelBufferGetPixelFormatType(imageBuffer)
        if pixelFormat == kCVPixelFormatType_32BGRA {
            let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer)
            return (imageBuffer, formatDescription)
        }
        
        // Se não for BGRA, fazemos a conversão
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        
        var convertedBuffer: CVPixelBuffer?
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:]
        ]
        
        // Cria um novo buffer no formato BGRA
        let status = CVPixelBufferCreate(kCFAllocatorDefault,
                                         width,
                                         height,
                                         kCVPixelFormatType_32BGRA,
                                         attributes as CFDictionary,
                                         &convertedBuffer)
        
        guard status == kCVReturnSuccess, let outputBuffer = convertedBuffer else {
            return (nil, nil)
        }
        
        // Faz a conversão usando Core Image
        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        let context = CIContext()
        
        CVPixelBufferLockBaseAddress(outputBuffer, [])
        context.render(ciImage, to: outputBuffer)
        CVPixelBufferUnlockBaseAddress(outputBuffer, [])
        
        // Cria um novo format description
        var formatDescription: CMFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: outputBuffer,
            formatDescriptionOut: &formatDescription
        )
        
        return (outputBuffer, formatDescription)
    }
}

extension DeviceCamera: AVCaptureVideoDataOutputSampleBufferDelegate {
    
    
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isCapturing else { return }
        limiter.processFrame(sampleBuffer)
        
    }
    
    private func processBuffer(sampleBuffer: CMSampleBuffer) {
        let (pixelBuffer, _) = convertToBGRA8888(sampleBuffer: sampleBuffer)
        
        guard let pixelBuffer = pixelBuffer else { return }
        
        processBGRAFrame(pixelBuffer: pixelBuffer)
    }
    
    private func processBGRAFrame(pixelBuffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer)!
        let pixelStride = bytesPerRow / width
        
        let data = Data(bytes: baseAddress, count: bytesPerRow * height)
        
        CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
        
        let result = [
            "bytes": data,
            "width": width,
            "height": height,
            "format": "BGRA8888",
            "planes": [[
                "bytes": data,
                "rowStride": bytesPerRow,
                "pixelStride": pixelStride,
            ]]
        ] as [String : Any?];
        
        FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", result)
        
    }
    
    
}
