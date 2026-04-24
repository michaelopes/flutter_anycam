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
                let rawFrame = TfFrame(width: rw, height: rh, bgra: rd, parentId: nil)
                let child = TfFrame(width: w, height: h, bgra: processed, parentId: rawFrame.id)
                frames.append(contentsOf: [rawFrame, child])
                return [
                    "id": child.id,
                    "width": w,
                    "height": h,
                    "rawWidth": rw,
                    "rawHeight": rh,
                ]
            } else {
                let f = TfFrame(width: w, height: h, bgra: processed, parentId: nil)
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

    /// Alinhado ao `TfFrame.close()` + `tryClose()` no Android: só remove quando não há filhos; depois propaga ao pai.
    func closeFrame(_ id: String) {
        queue.sync {
            guard let f = frames.first(where: { $0.id == id }) else { return }
            requestClose(f)
        }
    }

    private func hasChildren(_ f: TfFrame) -> Bool {
        frames.contains { $0.parentId == f.id }
    }

    private func canClose(_ f: TfFrame) -> Bool {
        f.closeCalled && !hasChildren(f) && !f.closed
    }

    /// `TfFrameHandler.close` no Android: marca intenção e tenta fechar.
    private func requestClose(_ f: TfFrame) {
        f.closeCalled = true
        tryClose(f)
    }

    /// Espelha `tryClose()` private no Android.
    private func tryClose(_ f: TfFrame) {
        if !canClose(f) { return }
        if f.closed { return }
        f.closed = true
        frames.removeAll { $0.id == f.id }

        guard let pid = f.parentId,
              let p = frames.first(where: { $0.id == pid }) else {
            return
        }
        if p.closeWhenChildClosed {
            requestClose(p)
        } else {
            tryClose(p)
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

            if let iz = inputSize, Int(iz.width) != w || Int(iz.height) != h {
                let dstW = Int(iz.width)
                let dstH = Int(iz.height)
                guard let piped = Self.centerCropAspectThenScale(
                    bgra: data,
                    width: w,
                    height: h,
                    dstWidth: dstW,
                    dstHeight: dstH
                ) else {
                    throw TfFrameError.resizeFailed
                }
                // Igual a `newInputFrame.closeWhenChildClosed = true` (Android) na pipeline crop/resize.
                let resized = TfFrame(
                    width: dstW, height: dstH, bgra: piped, parentId: parentId, closeWhenChildClosed: true
                )
                frames.append(resized)
                parentId = resized.id
                data = piped
                w = dstW
                h = dstH
            }

            if filter > 0 {
                data = Self.applyGrayscaleFilter(bgra: data, width: w, height: h)
            }

            let leaf = TfFrame(width: w, height: h, bgra: data, parentId: parentId)
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
            let nf = TfFrame(width: cw, height: ch, bgra: cropped, parentId: workFrame.id)
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

/// Espelha `TfFrame` no Android: fecho com `closeCalled` + `tryClose`, propagação ao `parentId` (e filhos bloqueiam fecho).
final class TfFrame {
    let id: String
    let width: Int
    let height: Int
    let bgra: Data
    let parentId: String?
    var closeWhenChildClosed: Bool
    var closeCalled: Bool
    var closed: Bool

    init(
        width: Int,
        height: Int,
        bgra: Data,
        parentId: String?,
        closeWhenChildClosed: Bool = true
    ) {
        self.id = UUID().uuidString
        self.width = width
        self.height = height
        self.bgra = bgra
        self.parentId = parentId
        self.closeWhenChildClosed = closeWhenChildClosed
        self.closeCalled = false
        self.closed = false
    }
}
