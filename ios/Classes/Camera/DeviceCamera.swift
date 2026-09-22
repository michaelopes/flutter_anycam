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
    private var sensorRotation: Int = 0;
    
    
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
        videoOutput?.setSampleBufferDelegate(self, queue: DispatchQueue(label: "camera.output"))
        videoOutput?.alwaysDiscardsLateVideoFrames = true
        videoOutput?.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        
        
     
        captureSession.beginConfiguration()
       // captureSession.sessionPreset = .inputPriority
        if !(captureSession is AVCaptureMultiCamSession) {
            captureSession.sessionPreset = .inputPriority
        }
        captureSession.addInput(input);
        
        do {
            try setClosestFormat();
        } catch {
            print("Erro ao configurar formato: \(error)")
            onFailed(message: "Error: \(error).")
            return;
        }
        
        sensorRotation = getFinalRotation(for: input.device.position);
        
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
        
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.startRunning()
            self.isCapturing = true
        }
        
     
        print("🧩 Starting camera setup")
        print("🧩 Input: \(input.device.localizedName)")
        print("🧩 Can add output: \(captureSession.canAddOutput(videoOutput!))")
        print("🧩 Delegate: \(videoOutput?.sampleBufferDelegate != nil)")
        print("🧩 Before startRunning: \(captureSession.isRunning)")
        
        for format in input.device.formats {
            let desc = format.formatDescription
            let dims = CMVideoFormatDescriptionGetDimensions(desc)
            print("Resolução \(dims.width)x\(dims.height)")
        }
        

        
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
    
    
    func setClosestFormat() throws {

        let device = videoInput!.device;
        let preferredWidth = Int32(preferredSize!.width);
        let preferredHeight = Int32(preferredSize!.height);
        
        try device.lockForConfiguration()

        var closestFormat: AVCaptureDevice.Format?
        var minDiff: Int32 = Int32.max

        for format in device.formats {

            let desc = format.formatDescription
            let dims = CMVideoFormatDescriptionGetDimensions(desc)
            
            if #available(iOS 13.0, *) {
                if captureSession is AVCaptureMultiCamSession {
                    guard format.isMultiCamSupported else { continue }
                }
            }
            
            guard CMFormatDescriptionGetMediaType(desc) == kCMMediaType_Video else { continue }
            
            let ranges = format.videoSupportedFrameRateRanges
            guard let range = ranges.first, range.maxFrameRate >= 30 else { continue }

            let diff = abs(dims.width - preferredWidth) +
                       abs(dims.height - preferredHeight)

            if diff < minDiff {
                minDiff = diff
                closestFormat = format
            }
        }

        if let bestFormat = closestFormat {
            device.activeFormat = bestFormat
        
            let desc = bestFormat.formatDescription
            let dims = CMVideoFormatDescriptionGetDimensions(desc)
    
            print("Selected: \(CMVideoFormatDescriptionGetDimensions(bestFormat.formatDescription).width)x\(CMVideoFormatDescriptionGetDimensions(bestFormat.formatDescription).height)")
            
        }


        device.unlockForConfiguration()
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
    
    override func setZoom(zoom: Float) -> Void {
        let value = CGFloat(zoom);
        if(videoInput!.device.activeFormat.videoMaxZoomFactor >= value) {
            let device = videoInput!.device;
            if (try? device.lockForConfiguration()) != nil {
                device.ramp(toVideoZoomFactor: value, withRate: 4.0)
                device.unlockForConfiguration()
            }
           
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
        
        guard var pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else {
            return
        }
        
        var width = CVPixelBufferGetWidth(pixelBuffer)
        var height = CVPixelBufferGetHeight(pixelBuffer)
        
        // Captura raw ANTES de qualquer processamento, só se necessário
        var rawImageBuffer: [String: Any]? = nil
        
        if resizeFrame != nil {
            // Lock original para capturar raw
            CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
            
            let rawWidth = width
            let rawHeight = height
            let rawBytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let bytesPerPixel = 4
            let rawRequiredBytesPerRow = rawWidth * bytesPerPixel
            
            if let rawBaseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) {
                let rawImageData: Data
                if rawBytesPerRow == rawRequiredBytesPerRow {
                    rawImageData = Data(bytes: rawBaseAddress, count: rawHeight * rawBytesPerRow)
                } else {
                    var data = Data(count: rawHeight * rawRequiredBytesPerRow)
                    data.withUnsafeMutableBytes { destPtr in
                        let src = rawBaseAddress.assumingMemoryBound(to: UInt8.self)
                        let dest = destPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                        for row in 0..<rawHeight {
                            memcpy(dest + row * rawRequiredBytesPerRow,
                                   src + row * rawBytesPerRow,
                                   rawRequiredBytesPerRow)
                        }
                    }
                    rawImageData = data
                }
                
                rawImageBuffer = [
                    "width": rawWidth,
                    "height": rawHeight,
                    "rotation": 0,
                    "format": "BGRA8888",
                    "planes": [[
                        "rowStride": rawRequiredBytesPerRow,
                        "pixelStride": bytesPerPixel,
                        "width": rawWidth,
                        "height": rawHeight,
                        "bytes": FlutterStandardTypedData(bytes: rawImageData)
                    ]],
                    "bytes": FlutterStandardTypedData(bytes: rawImageData)
                ]
            }
            
            CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
            
            // Agora aplica resize
            var targetWidth: Int = resizeFrame!.width
            var targetHeight: Int = resizeFrame!.height
            if targetWidth == -1 && targetHeight == -1 {
                let minSize = min(width, height)
                targetWidth = minSize
                targetHeight = minSize
            }
            if sensorRotation == 90 || sensorRotation == 270 {
                let temp = targetWidth
                targetWidth = targetHeight
                targetHeight = temp
            }
            pixelBuffer = ImageConverterUtil.resizeAspectFillAndCrop(
                pixelBuffer: pixelBuffer,
                targetWidth: targetWidth,
                targetHeight: targetHeight
            ) ?? pixelBuffer
            width = targetWidth
            height = targetHeight
        }
        
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
        
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let bytesPerPixel = 4
        let requiredBytesPerRow = width * bytesPerPixel
        
        ImageConverterUtil.applyFilter(
            baseAddress: baseAddress,
            width: width,
            height: height,
            bytesPerRow: requiredBytesPerRow,
            filter: filter
        )
        
        let imageData: Data
        if bytesPerRow == requiredBytesPerRow {
            imageData = Data(bytes: baseAddress, count: height * bytesPerRow)
        } else {
            var data = Data(count: height * requiredBytesPerRow)
            data.withUnsafeMutableBytes { destPtr in
                let src = baseAddress.assumingMemoryBound(to: UInt8.self)
                let dest = destPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                for row in 0..<height {
                    memcpy(dest + row * requiredBytesPerRow,
                           src + row * bytesPerRow,
                           requiredBytesPerRow)
                }
            }
            imageData = data
        }
        
        var imageBuffer: [String: Any] = [
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
        
        // Injeta rawFrame apenas quando resizeFrame está ativo
        if let raw = rawImageBuffer {
            imageBuffer["rawFrame"] = raw
        }

        if isTfMode {
            var rawBytes: Data?
            var rw: Int?
            var rh: Int?
            if let raw = rawImageBuffer,
               let rawTyped = raw["bytes"] as? FlutterStandardTypedData {
                rawBytes = rawTyped.data
                rw = raw["width"] as? Int
                rh = raw["height"] as? Int
            }
            let tfMeta = TfFrameHandler.shared.registerCameraFrame(
                processedBGRA: imageData,
                width: width,
                height: height,
                rawBGRA: rawBytes,
                rawWidth: rw,
                rawHeight: rh,
                filter: filter
            )
            for (k, v) in tfMeta {
                imageBuffer[k] = v
            }
        }
        
        onVideoFrameReceived(imageData: imageBuffer)
    }
    
   /* private func processBuffer(sampleBuffer: CMSampleBuffer) {
        
        guard var pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else {
            return
        }
        
        
        var width = CVPixelBufferGetWidth(pixelBuffer)
        var height = CVPixelBufferGetHeight(pixelBuffer)
        
        if(resizeFrame != nil) {
            var targetWidth: Int = resizeFrame!.width;
            var targetHeight: Int = resizeFrame!.height;
            if(targetWidth == -1 && targetHeight == -1) {
                let minSize = min(width, height);
                targetWidth = minSize;
                targetHeight = minSize;
            }
            if(sensorRotation == 90 || sensorRotation == 270) {
                let temp = targetWidth;
                targetWidth = targetHeight;
                targetHeight = temp;
            }
            pixelBuffer = ImageConverterUtil.resizeAspectFillAndCrop(pixelBuffer: pixelBuffer, targetWidth: targetWidth, targetHeight: targetHeight,) ?? pixelBuffer;
            width = targetWidth;
            height = targetHeight;
        }
        
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            return
        }
        
        let bytesPerPixel = 4
        let requiredBytesPerRow = width * bytesPerPixel
        
                
        ImageConverterUtil.applyFilter(baseAddress: baseAddress, width: width, height: height, bytesPerRow: requiredBytesPerRow, filter: filter);
        
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
        
    }*/
    
    
}
