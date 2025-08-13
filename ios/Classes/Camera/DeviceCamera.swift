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
        processBGRAFrame(sampleBuffer: sampleBuffer)
    }
    
    private func processBGRAFrame(sampleBuffer: CMSampleBuffer) {
        
        // Non-pixel buffer samples, such as audio samples, are ignored for streaming
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }
        
        
        // Must lock base address before accessing the pixel data
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        
        let imageWidth = CVPixelBufferGetWidth(pixelBuffer)
        let imageHeight = CVPixelBufferGetHeight(pixelBuffer)
        
        var planes: [[String: Any]] = []
        
        let isPlanar = CVPixelBufferIsPlanar(pixelBuffer)
        let planeCount = isPlanar ? CVPixelBufferGetPlaneCount(pixelBuffer) : 1
        
        for i in 0..<planeCount {
            let planeAddress: UnsafeMutableRawPointer?
            let bytesPerRow: Int
            let height: Int
            let width: Int
            
            if isPlanar {
                planeAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, i)
                bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, i)
                height = CVPixelBufferGetHeightOfPlane(pixelBuffer, i)
                width = CVPixelBufferGetWidthOfPlane(pixelBuffer, i)
            } else {
                planeAddress = CVPixelBufferGetBaseAddress(pixelBuffer)
                bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
                height = CVPixelBufferGetHeight(pixelBuffer)
                width = CVPixelBufferGetWidth(pixelBuffer)
            }
            
            let length = bytesPerRow * height
            let bytes = Data(bytes: planeAddress!, count: length)
            let pixelStride = bytesPerRow / width
            
            let planeBuffer: [String: Any] = [
                "rowStride": bytesPerRow,
                "pixelStride": pixelStride,
                "width": width,
                "height": height,
                "bytes": FlutterStandardTypedData(bytes: bytes),
            ]
            planes.append(planeBuffer)
        }
        
        // Lock the base address before accessing pixel data, and unlock it afterwards.
        // Done accessing the `pixelBuffer` at this point.
        CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
        
        
        let bytes = planes.first!["bytes"] as? FlutterStandardTypedData
        let imageBuffer: [String: Any] = [
            "width": imageWidth,
            "height": imageHeight,
            "format": "BGRA8888",
            "planes": planes,
            "bytes": bytes!,
        ]
        
        FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", imageBuffer)
        
        
        /*    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
         
         let width = CVPixelBufferGetWidth(pixelBuffer)
         let height = CVPixelBufferGetHeight(pixelBuffer)
         let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
         let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer)!
         let dataSize = CVPixelBufferGetDataSize(pixelBuffer)
         let pixelStride = bytesPerRow / width
         
         
         let pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
         print(String(format: "Pixel format: 0x%08X", pixelFormat))
         
         let data = Data(bytes: baseAddress, count: dataSize)
         
         CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
         
         let dt = convertImage(sampleBuffer: sampleBuffer)
         
         let result = [
         "bytes": FlutterStandardTypedData(bytes: ImageConverterUtil.convertBGRA8888ToJPEG(bgraData: data, width: width, height: height, quality: 100)!),
         "width": width,
         "height": height,
         "format": "BGRA8888",
         "planes": [[
         "bytes": FlutterStandardTypedData(bytes: data),
         "rowStride": bytesPerRow,
         "pixelStride": pixelStride,
         ]]
         ] as [String : Any?];
         
         FlutterEventStreamChannel.shared.send(self.viewId, "onVideoFrameReceived", result)*/
        
    }
    
    func convertPixelBufferToJPEG(pixelBuffer: CVPixelBuffer, compressionQuality: CGFloat = 0.8) -> Data? {
        // Cria um contexto de imagem Core Graphics a partir do pixel buffer
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        
        // Cria um contexto de renderização
        let context = CIContext(options: nil)
        
        // Renderiza a imagem CIImage para um CGImage
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
            return nil
        }
        
        // Converte o CGImage para UIImage
        let uiImage = UIImage(cgImage: cgImage)
        
        // Converte para JPEG com qualidade de compressão ajustável
        return uiImage.jpegData(compressionQuality: compressionQuality)
    }
    
    
    func convertImage(sampleBuffer: CMSampleBuffer) -> UIImage {
        let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)!
        let ciimage = CIImage(cvPixelBuffer: imageBuffer)
        let context = CIContext(options: nil)
        let cgImage = context.createCGImage(ciimage, from: ciimage.extent)!
        let image = UIImage(cgImage: cgImage)
        return image
    }
    
    
}
