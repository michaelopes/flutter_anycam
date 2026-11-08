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
    private var sensorRotation: Int = 0;
    private let outputQueue = DispatchQueue(label: "camera.output")
    
    
    /// Copy/filter/resize/register run here, off `outputQueue`: on that queue they would stall
    /// the preview, since late camera frames are discarded while it is busy.
    private let processingQueue = DispatchQueue(label: "camera.processing", qos: .userInitiated)
    private let processingLock = NSLock()
    /// At most one sample buffer is retained for processing, so the camera pool never starves.
    private var isProcessing = false

    private var isProcessingBusy: Bool {
        processingLock.lock()
        defer { processingLock.unlock() }
        return isProcessing
    }

    private func enqueueProcessing(_ sampleBuffer: CMSampleBuffer) {
        processingLock.lock()
        if isProcessing {
            processingLock.unlock()
            return
        }
        isProcessing = true
        processingLock.unlock()
        processingQueue.async { [weak self] in
            guard let self = self else { return }
            self.processBuffer(sampleBuffer: sampleBuffer)
            self.processingLock.lock()
            self.isProcessing = false
            self.processingLock.unlock()
        }
    }

    private lazy var limiter: FrameRateLimiterUtil<CMSampleBuffer> = {
        return FrameRateLimiterUtil<CMSampleBuffer>(targetFps: self.getFps()) { [weak self] sampleBuffer in
            self?.enqueueProcessing(sampleBuffer)
        } onFrameSkipped: { sampleBuffer in
            // Implementação opcional para frames pulados
        }
    }()

    /// Main thread (dispatched by BaseCamera.run). Session (re)configuration happens here and in
    /// `dispose`, both on main, so the two can never interleave; start/stop run on the
    /// session queue because they block.
    override func exec() -> Void {
        if isDisposed { return }
        guard let selector = self.cameraSelector,
              let input = AVCaptureUtil.shared.getCameraInput(cameraSelector: selector, owner: self) else {
            AVCaptureUtil.shared.cameraDidClose(self)
            onFailed(message: "Error: It is not possible to open two instances of the same camera.")
            return;
        }
        
        videoInput = input;
        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: outputQueue)
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        videoOutput = output

        var failure: String?
        captureSession.beginConfiguration()
        configure: do {
            // An open configuration block would freeze every camera sharing this session.
            defer { captureSession.commitConfiguration() }

            if !(captureSession is AVCaptureMultiCamSession) {
                captureSession.sessionPreset = .inputPriority
            }
            guard captureSession.canAddInput(input) else {
                failure = "Error: camera input cannot be added to the capture session."
                break configure
            }
            captureSession.addInput(input);

            if preferredSize != nil {
                do {
                    try setClosestFormat();
                } catch {
                    failure = "Error: \(error)."
                    break configure
                }
            }

            sensorRotation = getFinalRotation(for: input.device.position);

            guard captureSession.canAddOutput(output) else {
                failure = "Error: camera output cannot be added to the capture session."
                break configure
            }
            captureSession.addOutput(output)

            if #available(iOS 13.0, *),
               let multiCam = captureSession as? AVCaptureMultiCamSession,
               multiCam.hardwareCost > 1.0 {
                // Committing would make the whole shared session fail at runtime.
                failure = "Error: the device cannot run this camera alongside the open ones."
                break configure
            }

            let orientation = getVideoOrientation();
            for connection in output.connections {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = orientation;
                }
                if input.device.position == .front && connection.isVideoMirroringSupported {
                    connection.isVideoMirrored = true
                }
            }
        }

        if let message = failure {
            teardownSession()
            AVCaptureUtil.shared.cameraDidClose(self)
            onFailed(message: message)
            return
        }

        observeRuntimeErrors()
        let session = captureSession
        AVCaptureUtil.shared.sessionQueue.async { [weak self] in
            guard let self = self, !self.isDisposed else { return }
            if !session.isRunning {
                session.startRunning()
            }
        }

        let dimensions = CMVideoFormatDescriptionGetDimensions(input.device.activeFormat.formatDescription)
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

    /// Main thread only.
    private var isSuspended = false
    private var runtimeErrorObserver: NSObjectProtocol?

    /// A runtime error stops the shared session: without this, every preview would freeze
    /// and Dart would never hear about it.
    private func observeRuntimeErrors() {
        if runtimeErrorObserver != nil { return }
        runtimeErrorObserver = NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionRuntimeError,
            object: captureSession,
            queue: .main
        ) { [weak self] note in
            guard let self = self, !self.isDisposed, !self.isSuspended else { return }
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
            if error?.code == .mediaServicesWereReset {
                let session = self.captureSession
                AVCaptureUtil.shared.sessionQueue.async {
                    if !session.isRunning { session.startRunning() }
                }
            } else {
                self.onFailed(message: "Error: capture session failed (\(error?.localizedDescription ?? "unknown")).")
            }
        }
    }

    /// Main thread. Single-camera hardware: a newer camera took the device. The texture keeps
    /// its last frame and no event is sent, so an `autoRetry` cannot steal the device back.
    func suspendForOtherCamera() {
        guard !isDisposed, videoInput != nil else { return }
        isSuspended = true
        teardownSession(stopIfUnused: false)
    }

    /// Main thread. The device is free again: reopen with the same settings.
    func resumeFromSuspension() {
        guard isSuspended, !isDisposed else { return }
        isSuspended = false
        exec()
    }

    /// Main thread. Removes this camera's input/output; stops the shared session when unused.
    private func teardownSession(stopIfUnused: Bool = true) {
        if let observer = runtimeErrorObserver {
            NotificationCenter.default.removeObserver(observer)
            runtimeErrorObserver = nil
        }
        captureSession.beginConfiguration()
        if let output = videoOutput {
            output.setSampleBufferDelegate(nil, queue: nil)
            captureSession.removeOutput(output)
        }
        if let input = videoInput {
            AVCaptureUtil.shared.release(input: input)
        }
        captureSession.commitConfiguration()
        videoOutput = nil
        videoInput = nil

        guard stopIfUnused else { return }
        let session = captureSession
        AVCaptureUtil.shared.sessionQueue.async {
            if session.inputs.isEmpty && session.isRunning {
                session.stopRunning()
            }
        }
    }
    
    
    func setClosestFormat() throws {
        guard let device = videoInput?.device, let preferred = preferredSize else { return }
        let preferredWidth = Int32(preferred.width);
        let preferredHeight = Int32(preferred.height);
        
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }

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
    
    override func setZoom(zoom: Float) -> Void {
        guard let device = videoInput?.device else { return }
        let maxZoom = device.activeFormat.videoMaxZoomFactor
        let value = min(max(CGFloat(zoom), 1.0), maxZoom)
        if (try? device.lockForConfiguration()) != nil {
            device.ramp(toVideoZoomFactor: value, withRate: 4.0)
            device.unlockForConfiguration()
        }
    }

    /// `value` is an exposure bias in EV, clamped to what the device supports.
    override func setExposureCompensation(value: Int) -> Void {
        guard let device = videoInput?.device else { return }
        let bias = min(max(Float(value), device.minExposureTargetBias), device.maxExposureTargetBias)
        if (try? device.lockForConfiguration()) != nil {
            device.setExposureTargetBias(bias, completionHandler: nil)
            device.unlockForConfiguration()
        }
    }

    /// Main thread (CameraViewFactory.disposeView).
    override func dispose() -> Void {
        super.dispose()
        if videoInput != nil || videoOutput != nil {
            teardownSession()
        }
        AVCaptureUtil.shared.cameraDidClose(self)
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
    }
    
}

extension DeviceCamera: AVCaptureVideoDataOutputSampleBufferDelegate {
    
    
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !isDisposed else { return }
        guard CMSampleBufferDataIsReady(sampleBuffer) else {
            return
        }
        if let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            latestPixelBuffer = buffer
            frameAvailable();
        }
        // Skip the copy entirely while the previous frame is processed or waits for main.
        if canDeliverFrame() && !isProcessingBusy {
            limiter.processFrame(sampleBuffer)
        }
    }

    /// Tightly packed copy (rowStride = width * 4) of a locked BGRA buffer.
    private static func copyPacked(_ pixelBuffer: CVPixelBuffer) -> Data? {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let packedRow = width * 4
        if bytesPerRow == packedRow {
            return Data(bytes: base, count: height * packedRow)
        }
        var data = Data(count: height * packedRow)
        data.withUnsafeMutableBytes { destPtr in
            let src = base.assumingMemoryBound(to: UInt8.self)
            let dest = destPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
            for row in 0..<height {
                memcpy(dest + row * packedRow, src + row * bytesPerRow, packedRow)
            }
        }
        return data
    }

    private static func frameMap(_ data: Data, width: Int, height: Int) -> [String: Any] {
        let typed = FlutterStandardTypedData(bytes: data)
        return [
            "width": width,
            "height": height,
            "rotation": 0,
            "format": "BGRA8888",
            "planes": [[
                "rowStride": width * 4,
                "bytesPerRow": width * 4,
                "pixelStride": 4,
                "width": width,
                "height": height,
                "bytes": typed
            ]],
            "bytes": typed
        ]
    }
    
    private func processBuffer(sampleBuffer: CMSampleBuffer) {
        guard !isDisposed,
              var pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else {
            return
        }
        
        var width = CVPixelBufferGetWidth(pixelBuffer)
        var height = CVPixelBufferGetHeight(pixelBuffer)
        
        var rawData: Data? = nil
        var rawWidth = width
        var rawHeight = height
        if let resize = resizeFrame {
            rawData = Self.copyPacked(pixelBuffer)
            rawWidth = width
            rawHeight = height

            var targetWidth = resize.width
            var targetHeight = resize.height
            if targetWidth <= 0 && targetHeight <= 0 {
                let minSize = min(width, height)
                targetWidth = minSize
                targetHeight = minSize
            } else if targetWidth <= 0 {
                targetWidth = max(1, targetHeight * width / max(1, height))
            } else if targetHeight <= 0 {
                targetHeight = max(1, targetWidth * height / max(1, width))
            }
            if sensorRotation == 90 || sensorRotation == 270 {
                swap(&targetWidth, &targetHeight)
            }
            if let resized = ImageConverterUtil.resizeAspectFillAndCrop(
                pixelBuffer: pixelBuffer,
                targetWidth: targetWidth,
                targetHeight: targetHeight
            ) {
                pixelBuffer = resized
                width = targetWidth
                height = targetHeight
            }
        }
        
        // Always a private copy: the camera's buffer is also the preview texture and is
        // locked read-only, so it must never be filtered in place.
        guard var imageData = Self.copyPacked(pixelBuffer) else { return }
        if filter > 0 {
            imageData.withUnsafeMutableBytes { ptr in
                guard let base = ptr.baseAddress else { return }
                ImageConverterUtil.applyFilter(
                    baseAddress: base,
                    width: width,
                    height: height,
                    bytesPerRow: width * 4,
                    filter: filter
                )
            }
        }

        if isTfMode {
            if filter > 0, rawData != nil {
                rawData!.withUnsafeMutableBytes { ptr in
                    guard let base = ptr.baseAddress else { return }
                    ImageConverterUtil.applyFilter(
                        baseAddress: base,
                        width: rawWidth,
                        height: rawHeight,
                        bytesPerRow: rawWidth * 4,
                        filter: filter
                    )
                }
            }
            // Dart only gets the id; pixels stay native.
            guard let tfMeta = TfFrameHandler.shared.registerCameraFrame(
                processedBGRA: imageData,
                width: width,
                height: height,
                rawBGRA: rawData,
                rawWidth: rawData != nil ? rawWidth : nil,
                rawHeight: rawData != nil ? rawHeight : nil
            ) else {
                return
            }
            onVideoFrameReceived(imageData: tfMeta)
            return
        }

        var imageBuffer = Self.frameMap(imageData, width: width, height: height)
        if let raw = rawData {
            imageBuffer["rawFrame"] = Self.frameMap(raw, width: rawWidth, height: rawHeight)
        }
        onVideoFrameReceived(imageData: imageBuffer)
    }
    
}
