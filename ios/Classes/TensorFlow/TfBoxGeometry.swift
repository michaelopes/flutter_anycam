//
//  TfBoxGeometry.swift
//  flutter_anycam
//
//  Converte mapas de bounding box (Dart) para CGRect no espaço do frame de inferência.
//

import CoreGraphics
import Foundation

enum TfBoxGeometry {
    static func clampedRect(from map: [String: Any]) -> CGRect? {
        guard let xMin = (map["xMin"] as? NSNumber)?.intValue,
              let yMin = (map["yMin"] as? NSNumber)?.intValue,
              let xMax = (map["xMax"] as? NSNumber)?.intValue,
              let yMax = (map["yMax"] as? NSNumber)?.intValue,
              let srcW = (map["srcWidth"] as? NSNumber)?.intValue,
              let srcH = (map["srcHeight"] as? NSNumber)?.intValue else {
            return nil
        }
        let left = max(0, xMin)
        let top = max(0, yMin)
        let right = min(srcW, xMax)
        let bottom = min(srcH, yMax)
        let w = max(2, right - left)
        let h = max(2, bottom - top)
        return CGRect(x: left, y: top, width: w, height: h)
    }

    /// Versão simplificada de `getScaledRectSimple` (Android) — letterbox uniforme.
    static func scaledRect(from map: [String: Any], toSize: CGSize) -> CGRect? {
        guard let crop = clampedRect(from: map) else { return nil }
        guard let srcW = (map["srcWidth"] as? NSNumber)?.intValue,
              let srcH = (map["srcHeight"] as? NSNumber)?.intValue else {
            return nil
        }
        let origW = Int(toSize.width)
        let origH = Int(toSize.height)
        let inputW = srcW
        let inputH = srcH

        let cropLeft = Double(crop.minX)
        let cropTop = Double(crop.minY)
        let cropRight = Double(crop.maxX)
        let cropBottom = Double(crop.maxY)

        let scale = min(Double(inputW) / Double(origW), Double(inputH) / Double(origH))
        let scaledW = Double(origW) * scale
        let scaledH = Double(origH) * scale
        let padX = (Double(inputW) - scaledW) / 2
        let padY = (Double(inputH) - scaledH) / 2

        var left = (cropLeft - padX) / scale
        var top = (cropTop - padY) / scale
        var right = (cropRight - padX) / scale
        var bottom = (cropBottom - padY) / scale

        left = max(0, left)
        top = max(0, top)
        right = min(Double(origW), right)
        bottom = min(Double(origH), bottom)

        var bboxW = right - left
        var bboxH = bottom - top
        if bboxW <= 1 || bboxH <= 1 {
            return CGRect(x: 0, y: 0, width: 2, height: 2)
        }

        let padPct = (map["cropPadPercent"] as? NSNumber)?.doubleValue ?? 0
        let padFactor = padPct / 100
        let padXBox = bboxW * padFactor
        let padYBox = bboxH * padFactor
        left -= padXBox
        top -= padYBox
        right += padXBox
        bottom += padYBox

        left = max(0, left)
        top = max(0, top)
        right = min(Double(origW), right)
        bottom = min(Double(origH), bottom)

        var finalLeft = Int(floor(left)) & ~1
        var finalTop = Int(floor(top)) & ~1
        var finalRight = (Int(ceil(right)) + 1) & ~1
        var finalBottom = (Int(ceil(bottom)) + 1) & ~1

        finalLeft = max(0, finalLeft)
        finalTop = max(0, finalTop)
        finalRight = min(origW, finalRight)
        finalBottom = min(origH, finalBottom)

        if finalRight - finalLeft < 2 { finalRight = min(origW, finalLeft + 2) }
        if finalBottom - finalTop < 2 { finalBottom = min(origH, finalTop + 2) }

        if finalRight <= finalLeft || finalBottom <= finalTop {
            return CGRect(x: 0, y: 0, width: 2, height: 2)
        }

        return CGRect(x: finalLeft, y: finalTop, width: finalRight - finalLeft, height: finalBottom - finalTop)
    }
}
