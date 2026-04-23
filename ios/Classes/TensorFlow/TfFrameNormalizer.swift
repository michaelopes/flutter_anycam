//
//  TfFrameNormalizer.swift
//  flutter_anycam
//
//  Converte BGRA8888 (câmera iOS) para buffer NHWC RGB no formato esperado pelo TFLite.
//  Ordem de canais na saída: R, G, B (alinhado ao pipeline Android).
//

import Foundation

enum TfInputElementKind {
    case float32
    case uint8
    case int8
}

enum TfNormalizeMode: String {
    case none = "none"
    case simple = "simple"
    case centered = "centered"
    case imagenet = "imagenet"
}

final class TfFrameNormalizer {
    static let shared = TfFrameNormalizer()
    private init() {}

    /// `invScale` e `zeroPoint` vêm do tensor de entrada quantizado (1/scale e zp).
    func normalize(
        bgra: Data,
        width: Int,
        height: Int,
        normalize: String,
        inputKind: TfInputElementKind,
        invScale: Float,
        zeroPoint: Int
    ) throws -> Data {
        let mode = TfNormalizeMode(rawValue: normalize) ?? .none
        let pixelCount = width * height
        let src = [UInt8](bgra)

        switch inputKind {
        case .float32:
            var buf = [Float](repeating: 0, count: pixelCount * 3)
            var wi = 0
            applyNormalization(
                src: src,
                width: width,
                height: height,
                mode: mode,
                writer: { r, g, b in
                    buf[wi] = r
                    buf[wi + 1] = g
                    buf[wi + 2] = b
                    wi += 3
                }
            )
            return buf.withUnsafeBufferPointer { Data(buffer: $0) }
        case .uint8:
            var bytes = [UInt8](repeating: 0, count: pixelCount * 3)
            var idx = 0
            applyNormalization(
                src: src,
                width: width,
                height: height,
                mode: mode,
                writer: { r, g, b in
                    bytes[idx] = UInt8(clamping: Int(round(r)))
                    bytes[idx + 1] = UInt8(clamping: Int(round(g)))
                    bytes[idx + 2] = UInt8(clamping: Int(round(b)))
                    idx += 3
                }
            )
            return Data(bytes)
        case .int8:
            var bytes = [Int8](repeating: 0, count: pixelCount * 3)
            var idx = 0
            applyNormalization(
                src: src,
                width: width,
                height: height,
                mode: mode,
                writer: { r, g, b in
                    let qr = Int(round(r * invScale)) + zeroPoint
                    let qg = Int(round(g * invScale)) + zeroPoint
                    let qb = Int(round(b * invScale)) + zeroPoint
                    bytes[idx] = Int8(clamping: qr)
                    bytes[idx + 1] = Int8(clamping: qg)
                    bytes[idx + 2] = Int8(clamping: qb)
                    idx += 3
                }
            )
            return Data(bytes: bytes, count: bytes.count)
        }
    }

    private func applyNormalization(
        src: [UInt8],
        width: Int,
        height: Int,
        mode: TfNormalizeMode,
        writer: (_ r: Float, _ g: Float, _ b: Float) -> Void
    ) {
        let rowStride = width * 4
        var scaleR: Float = 1, scaleG: Float = 1, scaleB: Float = 1
        var offR: Float = 0, offG: Float = 0, offB: Float = 0
        switch mode {
        case .simple:
            scaleR = 1 / 255; scaleG = 1 / 255; scaleB = 1 / 255
        case .centered:
            scaleR = 1 / 127.5; scaleG = 1 / 127.5; scaleB = 1 / 127.5
            offR = -1; offG = -1; offB = -1
        case .imagenet:
            scaleR = 1 / (255 * 0.229)
            scaleG = 1 / (255 * 0.224)
            scaleB = 1 / (255 * 0.225)
            offR = -0.485 / 0.229
            offG = -0.456 / 0.224
            offB = -0.406 / 0.225
        case .none:
            break
        }

        for y in 0..<height {
            for x in 0..<width {
                let o = y * rowStride + x * 4
                let b = Float(src[o])
                let g = Float(src[o + 1])
                let r = Float(src[o + 2])
                writer(r * scaleR + offR, g * scaleG + offG, b * scaleB + offB)
            }
        }
    }
}

private extension Int8 {
    init(clamping i: Int) {
        if i > 127 { self = 127 }
        else if i < -128 { self = -128 }
        else { self = Int8(truncatingIfNeeded: i) }
    }
}
