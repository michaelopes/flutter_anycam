//
//  TfFrameHandler.swift
//  flutter_anycam
//
//  Gestão de frames BGRA para TFLite (espelho funcional do Android TfFrameHandler).
//
//  Ownership (same model as Android):
//  - `close(id)` ends the owner's (Dart's) reference; it is idempotent.
//  - A frame is freed once its owner released it, it has no live children and no pins.
//  - Children keep their parent alive. Only internal frames (`closeWhenChildClosed`: the raw
//    camera frame behind a resized one, the letterbox crop) are released with their child;
//    frames Dart holds are never closed behind its back.
//  - Inference results pin the frame they reference.
//

import Accelerate
import CoreGraphics
import Flutter
import Foundation
import UIKit

final class TfFrameHandler {
    static let shared = TfFrameHandler()

    /// Camera frames alive at once. A consumer that stops closing frames gets dropped frames
    /// instead of running out of memory.
    static let maxSourceFrames = 12

    private let queue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.tf.frames")
    private var frames: [String: TfFrame] = [:]
    private var sourceFrames = 0
    private var droppedFrames = 0

    private init() {}

    // MARK: - Registo (câmera)

    /// Returns the map sent to Dart (`id`, `width`, `height`, optional `rawWidth`/`rawHeight`),
    /// or nil when too many camera frames are still open (the frame is dropped).
    func registerCameraFrame(
        processedBGRA: Data,
        width: Int,
        height: Int,
        rawBGRA: Data?,
        rawWidth: Int?,
        rawHeight: Int?
    ) -> [String: Any]? {
        queue.sync {
            if sourceFrames >= Self.maxSourceFrames {
                if droppedFrames % 30 == 0 {
                    NSLog("[flutter_anycam] Dropping camera frame: \(sourceFrames) frames still open. Close every FlutterAnycamTfFrame after use.")
                }
                droppedFrames += 1
                return nil
            }
            if let rData = rawBGRA, let rw = rawWidth, let rh = rawHeight {
                let rawFrame = TfFrame(width: rw, height: rh, bgra: rData, parentId: nil, isSource: true)
                rawFrame.closeWhenChildClosed = true
                register(rawFrame)
                let child = TfFrame(width: width, height: height, bgra: processedBGRA, parentId: rawFrame.id)
                register(child)
                return [
                    "id": child.id,
                    "width": width,
                    "height": height,
                    "rawWidth": rw,
                    "rawHeight": rh,
                ]
            }
            let f = TfFrame(width: width, height: height, bgra: processedBGRA, parentId: nil, isSource: true)
            register(f)
            return toFrameMap(f)
        }
    }

    /// Queue only.
    private func register(_ f: TfFrame) {
        frames[f.id] = f
        if f.isSource { sourceFrames += 1 }
        if let pid = f.parentId, let parent = frames[pid] {
            parent.children += 1
        }
    }

    func getFramesSize() -> Int {
        queue.sync { frames.count }
    }

    func getFrameById(_ id: String) -> TfFrame? {
        queue.sync { frames[id] }
    }

    func parentOf(_ id: String) -> TfFrame? {
        queue.sync {
            guard let pid = frames[id]?.parentId else { return nil }
            return frames[pid]
        }
    }

    /// Owner release (Dart `close()`). Idempotent.
    func closeFrame(_ id: String) {
        queue.sync {
            guard let f = frames[id], !f.ownerReleased else { return }
            f.ownerReleased = true
            tryRelease(f)
        }
    }

    /// Keeps `id` alive for a holder other than its owner (e.g. an inference result).
    func pin(_ id: String) -> Bool {
        queue.sync {
            guard let f = frames[id] else { return false }
            f.pins += 1
            return true
        }
    }

    func unpin(_ id: String) {
        queue.sync {
            guard let f = frames[id] else { return }
            f.pins = max(0, f.pins - 1)
            tryRelease(f)
        }
    }

    func closeAll() {
        queue.sync {
            frames.removeAll()
            sourceFrames = 0
        }
    }

    /// Queue only.
    private func tryRelease(_ f: TfFrame) {
        guard f.ownerReleased, f.children == 0, f.pins == 0, frames[f.id] != nil else { return }
        frames.removeValue(forKey: f.id)
        if f.isSource { sourceFrames = max(0, sourceFrames - 1) }

        guard let pid = f.parentId, let parent = frames[pid] else { return }
        parent.children = max(0, parent.children - 1)
        if parent.closeWhenChildClosed {
            parent.ownerReleased = true
        }
        tryRelease(parent)
    }

    func getFrameJpeg(frameId: String) -> Data? {
        guard let f = getFrameById(frameId) else { return nil }
        return ImageConverterUtil.convertBGRA8888ToJPEG(
            bgraData: f.bgra,
            width: f.width,
            height: f.height,
            quality: 1.0,
            filter: 0,
            crop: nil
        )
    }

    func registerFrameFromJpeg(_ jpeg: Data) -> [String: Any]? {
        guard let image = UIImage(data: jpeg), let cgImage = image.cgImage else {
            return nil
        }
        let w = cgImage.width
        let h = cgImage.height
        guard w > 0, h > 0 else { return nil }
        var bgra = Data(count: w * h * 4)
        var drawn = false
        bgra.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            guard let ctx = CGContext(
                data: base,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                    | CGBitmapInfo.byteOrder32Little.rawValue
            ) else { return }
            ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
            drawn = true
        }
        guard drawn else { return nil }
        let frame = TfFrame(width: w, height: h, bgra: bgra, parentId: nil)
        queue.sync { register(frame) }
        return toFrameMap(frame)
    }

    /// Independent frame (no parent): its lifetime does not depend on the source.
    func registerFrameCopy(_ frameId: String) -> [String: Any]? {
        guard let src = getFrameById(frameId) else { return nil }
        let frame = TfFrame(width: src.width, height: src.height, bgra: src.bgra, parentId: nil)
        queue.sync { register(frame) }
        return toFrameMap(frame)
    }

    /// Independent frame (no parent), like Android.
    func registerFrameCrop(
        _ frameId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        resizeWidth: Int?,
        resizeHeight: Int?
    ) -> [String: Any]? {
        guard let src = getFrameById(frameId) else { return nil }
        let left = max(0, x)
        let top = max(0, y)
        let right = min(src.width, x + width)
        let bottom = min(src.height, y + height)
        guard right > left, bottom > top else { return nil }

        let rect = CGRect(x: left, y: top, width: right - left, height: bottom - top)
        guard var cropped = Self.cropBgra(bgra: src.bgra, width: src.width, height: src.height, rect: rect) else {
            return nil
        }

        var cw = Int(rect.width)
        var ch = Int(rect.height)
        if let resizeWidth, let resizeHeight,
           resizeWidth > 0, resizeHeight > 0,
           (cw != resizeWidth || ch != resizeHeight) {
            guard let scaled = Self.scaleBgraBilinear(
                bgra: cropped,
                width: cw,
                height: ch,
                dstWidth: resizeWidth,
                dstHeight: resizeHeight
            ) else {
                return nil
            }
            cropped = scaled
            cw = resizeWidth
            ch = resizeHeight
        }

        let frame = TfFrame(width: cw, height: ch, bgra: cropped, parentId: nil)
        queue.sync { register(frame) }
        return toFrameMap(frame)
    }

    /// Normalized ROI (0..1). Non-finite values (a Dart division by zero) would trap in the
    /// Int conversions downstream, so they are clamped or rejected here.
    private static func roiRect(_ f: TfFrame, xMin rawXMin: Double, yMin rawYMin: Double, xMax rawXMax: Double, yMax rawYMax: Double) -> CGRect? {
        func unit(_ v: Double) -> Double? { v.isNaN ? nil : min(max(v, 0), 1) }
        guard let xMin = unit(rawXMin), let yMin = unit(rawYMin),
              let xMax = unit(rawXMax), let yMax = unit(rawYMax),
              xMax > xMin, yMax > yMin else { return nil }
        return CGRect(
            x: xMin * Double(f.width),
            y: yMin * Double(f.height),
            width: (xMax - xMin) * Double(f.width),
            height: (yMax - yMin) * Double(f.height)
        )
    }

    func computeBlurScore(
        frameId: String,
        xMin: Double,
        yMin: Double,
        xMax: Double,
        yMax: Double,
        sampleStep: Int
    ) -> Double? {
        guard let f = getFrameById(frameId) else { return nil }
        return BlurUtil.laplacianVariance(
            bgra: f.bgra,
            width: f.width,
            height: f.height,
            roi: Self.roiRect(f, xMin: xMin, yMin: yMin, xMax: xMax, yMax: yMax),
            sampleStep: max(1, sampleStep)
        )
    }

    func computeIlluminationScore(
        frameId: String,
        xMin: Double,
        yMin: Double,
        xMax: Double,
        yMax: Double,
        sampleStep: Int
    ) -> [String: Double]? {
        guard let f = getFrameById(frameId) else { return nil }
        let stats = IlluminationUtil.stats(
            bgra: f.bgra,
            width: f.width,
            height: f.height,
            roi: Self.roiRect(f, xMin: xMin, yMin: yMin, xMax: xMax, yMax: yMax),
            sampleStep: max(1, sampleStep)
        )
        return [
            "mean": stats.mean,
            "darkPixelRatio": stats.darkPixelRatio,
            "brightPixelRatio": stats.brightPixelRatio,
        ]
    }

    // MARK: - Tensor

    struct PreparedFrame {
        let frameId: String
        let bgra: Data
        let width: Int
        let height: Int
    }

    /// Creates the inference frame as a child of the input (keeping the input alive while it
    /// is in use). The caller owns the returned frame and must `closeFrame` it.
    func prepareInputFrame(
        inputFrameId: String,
        inputSize: CGSize?,
        filter: Int
    ) throws -> PreparedFrame {
        guard let src = getFrameById(inputFrameId), pin(inputFrameId) else {
            throw TfFrameError.frameNotFound
        }
        defer { unpin(inputFrameId) }

        var data = src.bgra
        var w = src.width
        var h = src.height
        var parentId = src.id
        var letterbox: TfFrame?

        if let iz = inputSize, Int(iz.width) > 0, Int(iz.height) > 0,
           Int(iz.width) != w || Int(iz.height) != h {
            let dstW = Int(iz.width)
            let dstH = Int(iz.height)
            guard let cropped = Self.centerCropToAspect(bgra: data, width: w, height: h, dstWidth: dstW, dstHeight: dstH) else {
                throw TfFrameError.resizeFailed
            }
            let (crop, cropRect) = cropped
            guard let scaled = Self.scaleBgraBilinear(
                    bgra: crop, width: Int(cropRect.width), height: Int(cropRect.height),
                    dstWidth: dstW, dstHeight: dstH
                  ) else {
                throw TfFrameError.resizeFailed
            }
            // Internal letterbox (full-resolution crop): used for scaled crops, released with
            // the inference frame.
            let lb = TfFrame(width: Int(cropRect.width), height: Int(cropRect.height), bgra: crop, parentId: src.id)
            lb.closeWhenChildClosed = true
            letterbox = lb
            parentId = lb.id
            data = scaled
            w = dstW
            h = dstH
        }

        if filter > 0 {
            data = Self.applyGrayscaleFilter(bgra: data, width: w, height: h)
        }

        let leaf = TfFrame(width: w, height: h, bgra: data, parentId: parentId)
        let registered: Bool = queue.sync {
            guard frames[src.id] != nil else { return false }
            if let lb = letterbox { register(lb) }
            register(leaf)
            return true
        }
        guard registered else { throw TfFrameError.frameNotFound }
        return PreparedFrame(frameId: leaf.id, bgra: data, width: w, height: h)
    }

    /// Independent crop of the inference frame (or, scaled, of its full-resolution parent).
    func newFrameCroppedById(frameId: String, box: [String: Any]?, enableScale: Bool) -> TfFrame? {
        guard let box = box, let srcFrame = getFrameById(frameId) else { return nil }

        let workFrame: TfFrame
        let rect: CGRect?
        if enableScale, let parent = parentOf(frameId) {
            workFrame = parent
            rect = TfBoxGeometry.scaledRect(from: box, toSize: CGSize(width: parent.width, height: parent.height))
        } else {
            workFrame = srcFrame
            rect = TfBoxGeometry.clampedRect(from: box)
        }

        guard let crop = rect,
              let cropped = Self.cropBgra(bgra: workFrame.bgra, width: workFrame.width, height: workFrame.height, rect: crop) else {
            return nil
        }
        let x = max(0, Int(crop.minX))
        let y = max(0, Int(crop.minY))
        let cw = min(workFrame.width - x, Int(crop.width))
        let ch = min(workFrame.height - y, Int(crop.height))
        let nf = TfFrame(width: cw, height: ch, bgra: cropped, parentId: nil)
        queue.sync { register(nf) }
        return nf
    }

    func toFrameMap(_ f: TfFrame) -> [String: Any] {
        [
            "id": f.id,
            "width": f.width,
            "height": f.height,
        ]
    }

    // MARK: - BGRA

    private static func centerCropToAspect(
        bgra: Data,
        width: Int,
        height: Int,
        dstWidth: Int,
        dstHeight: Int
    ) -> (Data, CGRect)? {
        let dstAspect = Float(dstWidth) / Float(dstHeight)
        let srcAspect = Float(width) / Float(height)
        let cropW: Int
        let cropH: Int
        if srcAspect > dstAspect {
            cropH = height
            cropW = max(1, Int(Float(height) * dstAspect))
        } else {
            cropW = width
            cropH = max(1, Int(Float(width) / dstAspect))
        }
        let rect = CGRect(x: (width - cropW) / 2, y: (height - cropH) / 2, width: cropW, height: cropH)
        guard let cropped = cropBgra(bgra: bgra, width: width, height: height, rect: rect) else {
            return nil
        }
        return (cropped, rect)
    }

    private static func cropBgra(bgra: Data, width: Int, height: Int, rect: CGRect) -> Data? {
        guard rect.minX.isFinite, rect.minY.isFinite, rect.width.isFinite, rect.height.isFinite else { return nil }
        let x = max(0, Int(rect.minX))
        let y = max(0, Int(rect.minY))
        let cw = min(width - x, Int(rect.width))
        let ch = min(height - y, Int(rect.height))
        guard cw > 0, ch > 0, bgra.count >= width * height * 4 else { return nil }
        let srcRow = width * 4
        let dstRow = cw * 4
        if x == 0 && y == 0 && cw == width && ch == height {
            return bgra
        }
        var out = Data(count: ch * dstRow)
        bgra.withUnsafeBytes { raw in
            guard let s = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            out.withUnsafeMutableBytes { dstRaw in
                guard let d = dstRaw.bindMemory(to: UInt8.self).baseAddress else { return }
                for row in 0..<ch {
                    memcpy(d + row * dstRow, s + (y + row) * srcRow + x * 4, dstRow)
                }
            }
        }
        return out
    }

    /// Channel-agnostic 4-byte-per-pixel scale (`vImageScale_ARGB8888` treats the four
    /// channels identically, so BGRA stays BGRA).
    private static func scaleBgraBilinear(bgra: Data, width: Int, height: Int, dstWidth: Int, dstHeight: Int) -> Data? {
        guard width > 0, height > 0, dstWidth > 0, dstHeight > 0, bgra.count >= width * height * 4 else { return nil }
        var srcCopy = bgra
        let dstRow = dstWidth * 4
        var dstData = Data(count: dstHeight * dstRow)
        var err: vImage_Error = kvImageNoError
        srcCopy.withUnsafeMutableBytes { srcRaw in
            dstData.withUnsafeMutableBytes { dstRaw in
                guard let srcBase = srcRaw.baseAddress, let dstBase = dstRaw.baseAddress else {
                    err = kvImageNullPointerArgument
                    return
                }
                var srcBuf = vImage_Buffer(
                    data: srcBase,
                    height: vImagePixelCount(height),
                    width: vImagePixelCount(width),
                    rowBytes: width * 4
                )
                var dstBuf = vImage_Buffer(
                    data: dstBase,
                    height: vImagePixelCount(dstHeight),
                    width: vImagePixelCount(dstWidth),
                    rowBytes: dstRow
                )
                err = vImageScale_ARGB8888(&srcBuf, &dstBuf, nil, vImage_Flags(kvImageHighQualityResampling))
            }
        }
        guard err == kvImageNoError else { return nil }
        return dstData
    }

    private static func applyGrayscaleFilter(bgra: Data, width: Int, height: Int) -> Data {
        var copy = bgra
        copy.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            ImageConverterUtil.applyFilter(
                baseAddress: base,
                width: width,
                height: height,
                bytesPerRow: width * 4,
                filter: 1
            )
        }
        return copy
    }
}

enum TfFrameError: Error {
    case frameNotFound
    case resizeFailed
}

/// Mutable state is only touched on `TfFrameHandler.queue`; pixel data is immutable.
final class TfFrame {
    let id: String
    let width: Int
    let height: Int
    let bgra: Data
    let parentId: String?
    let isSource: Bool
    var closeWhenChildClosed = false
    var ownerReleased = false
    var children = 0
    var pins = 0

    init(width: Int, height: Int, bgra: Data, parentId: String?, isSource: Bool = false) {
        self.id = UUID().uuidString
        self.width = width
        self.height = height
        self.bgra = bgra
        self.parentId = parentId
        self.isSource = isSource
    }
}
