//
//  TfFrameHandler.swift
//  flutter_anycam
//
//  Gestão de frames BGRA para TFLite (espelho funcional do Android TfFrameHandler).
//

import Accelerate
import CoreGraphics
import Flutter
import Foundation
import UIKit

final class TfFrameHandler {
    static let shared = TfFrameHandler()

    private let queue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.tf.frames")
    private var frames: [TfFrame] = []

    private init() {}

    // MARK: - Registo (câmera)

    /// Atributos a fundir no mapa enviado ao Dart: `id`, `width`, `height`, opcionalmente `rawWidth` / `rawHeight`.
    func registerCameraFrame(
        processedBGRA: Data,
        width: Int,
        height: Int,
        rawBGRA: Data?,
        rawWidth: Int?,
        rawHeight: Int?,
        filter: Int
    ) -> [String: Any] {
        queue.sync {
            var processed = processedBGRA
            var w = width
            var h = height
            if filter > 0 {
                processed = Self.applyGrayscaleFilter(bgra: processed, width: w, height: h)
            }

            if let rData = rawBGRA, let rw = rawWidth, let rh = rawHeight {
                var rd = rData
                if filter > 0 {
                    rd = Self.applyGrayscaleFilter(bgra: rd, width: rw, height: rh)
                }
                let rawFrame = TfFrame(id: UUID().uuidString, width: rw, height: rh, bgra: rd, parentId: nil)
                let child = TfFrame(id: UUID().uuidString, width: w, height: h, bgra: processed, parentId: rawFrame.id)
                frames.append(contentsOf: [rawFrame, child])
                return [
                    "id": child.id,
                    "width": w,
                    "height": h,
                    "rawWidth": rw,
                    "rawHeight": rh,
                ]
            } else {
                let f = TfFrame(id: UUID().uuidString, width: w, height: h, bgra: processed, parentId: nil)
                frames.append(f)
                return [
                    "id": f.id,
                    "width": w,
                    "height": h,
                ]
            }
        }
    }

    func getFramesSize() -> Int {
        queue.sync { frames.count }
    }

    func getFrameById(_ id: String) -> TfFrame? {
        queue.sync { frames.first { $0.id == id } }
    }

    func closeFrame(_ id: String) {
        queue.sync {
            removeFrameTree(id: id)
        }
    }

    func getFrameJpeg(frameId: String) -> Data? {
        queue.sync {
            guard let f = frames.first(where: { $0.id == frameId }) else { return nil }
            return ImageConverterUtil.convertBGRA8888ToJPEG(
                bgraData: f.bgra,
                width: f.width,
                height: f.height,
                quality: 1.0,
                filter: 0,
                crop: nil
            )
        }
    }

    // MARK: - Tensor

    struct PreparedFrame {
        let frameId: String
        let bgra: Data
        let width: Int
        let height: Int
    }

    func prepareInputFrame(
        inputFrameId: String,
        inputSize: CGSize?,
        filter: Int
    ) throws -> PreparedFrame {
        try queue.sync {
            guard let src = frames.first(where: { $0.id == inputFrameId }) else {
                throw TfFrameError.frameNotFound
            }
            var data = src.bgra
            var w = src.width
            var h = src.height
            var parentId: String? = src.id

            if let is = inputSize, Int(is.width) != w || Int(is.height) != h {
                let dstW = Int(is.width)
                let dstH = Int(is.height)
                guard let piped = Self.centerCropAspectThenScale(
                    bgra: data,
                    width: w,
                    height: h,
                    dstWidth: dstW,
                    dstHeight: dstH
                ) else {
                    throw TfFrameError.resizeFailed
                }
                let resized = TfFrame(id: UUID().uuidString, width: dstW, height: dstH, bgra: piped, parentId: parentId)
                frames.append(resized)
                parentId = resized.id
                data = piped
                w = dstW
                h = dstH
            }

            if filter > 0 {
                data = Self.applyGrayscaleFilter(bgra: data, width: w, height: h)
            }

            let leaf = TfFrame(id: UUID().uuidString, width: w, height: h, bgra: data, parentId: parentId)
            frames.append(leaf)
            return PreparedFrame(frameId: leaf.id, bgra: data, width: w, height: h)
        }
    }

    func newFrameCroppedById(frameId: String, box: [String: Any]?, enableScale: Bool) -> TfFrame? {
        queue.sync {
            guard let srcFrame = frames.first(where: { $0.id == frameId }) else { return nil }
            guard let box = box else { return srcFrame }

            let workFrame: TfFrame
            let rect: CGRect?
            if enableScale {
                if let pid = srcFrame.parentId, let parent = frames.first(where: { $0.id == pid }) {
                    workFrame = parent
                    rect = TfBoxGeometry.scaledRect(from: box, toSize: CGSize(width: parent.width, height: parent.height))
                } else {
                    workFrame = srcFrame
                    rect = TfBoxGeometry.scaledRect(from: box, toSize: CGSize(width: srcFrame.width, height: srcFrame.height))
                }
            } else {
                workFrame = srcFrame
                rect = TfBoxGeometry.clampedRect(from: box)
            }

            guard let crop = rect else { return nil }
            guard let cropped = Self.cropBgra(bgra: workFrame.bgra, width: workFrame.width, height: workFrame.height, rect: crop) else {
                return nil
            }
            let cw = Int(crop.width)
            let ch = Int(crop.height)
            let nf = TfFrame(id: UUID().uuidString, width: cw, height: ch, bgra: cropped, parentId: workFrame.id)
            frames.append(nf)
            return nf
        }
    }

    func toFrameMap(_ f: TfFrame) -> [String: Any] {
        [
            "id": f.id,
            "width": f.width,
            "height": f.height,
        ]
    }

    // MARK: - BGRA

    private func removeFrameTree(id: String) {
        let children = frames.filter { $0.parentId == id }
        for c in children {
            removeFrameTree(id: c.id)
        }
        frames.removeAll { $0.id == id }
    }

    private static func centerCropAspectThenScale(
        bgra: Data,
        width: Int,
        height: Int,
        dstWidth: Int,
        dstHeight: Int
    ) -> Data? {
        let dstAspect = Float(dstWidth) / Float(dstHeight)
        let srcAspect = Float(width) / Float(height)
        let cropW: Int
        let cropH: Int
        if srcAspect > dstAspect {
            cropH = height
            cropW = Int(Float(height) * dstAspect)
        } else {
            cropW = width
            cropH = Int(Float(width) / dstAspect)
        }
        let ox = (width - cropW) / 2
        let oy = (height - cropH) / 2
        guard let cropped = cropBgra(bgra: bgra, width: width, height: height, rect: CGRect(x: ox, y: oy, width: cropW, height: cropH)) else {
            return nil
        }
        return scaleBgraBilinear(bgra: cropped, width: cropW, height: cropH, dstWidth: dstWidth, dstHeight: dstHeight)
    }

    private static func cropBgra(bgra: Data, width: Int, height: Int, rect: CGRect) -> Data? {
        let x = max(0, Int(rect.minX))
        let y = max(0, Int(rect.minY))
        let cw = min(width - x, Int(rect.width))
        let ch = min(height - y, Int(rect.height))
        guard cw > 0, ch > 0 else { return nil }
        let srcRow = width * 4
        let dstRow = cw * 4
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

    /// Nota: `vImageScale_ARGB8888` assume layout ARGB; entrada BGRA pode trocar R/B face ao modelo — validar com o teu `.tflite`.
    private static func scaleBgraBilinear(bgra: Data, width: Int, height: Int, dstWidth: Int, dstHeight: Int) -> Data? {
        var srcCopy = bgra
        let dstRow = dstWidth * 4
        var dstData = Data(count: dstHeight * dstRow)
        var err: vImage_Error = kvImageNoError
        srcCopy.withUnsafeMutableBytes { srcRaw in
            dstData.withUnsafeMutableBytes { dstRaw in
                var srcBuf = vImage_Buffer(
                    data: srcRaw.baseAddress!,
                    height: vImagePixelCount(height),
                    width: vImagePixelCount(width),
                    rowBytes: width * 4
                )
                var dstBuf = vImage_Buffer(
                    data: dstRaw.baseAddress!,
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

struct TfFrame {
    let id: String
    let width: Int
    let height: Int
    let bgra: Data
    let parentId: String?
}
