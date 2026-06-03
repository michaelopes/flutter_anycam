//
//  IlluminationUtil.swift
//  flutter_anycam
//
//  Mean luminance and clipped pixel ratios on BGRA8888 frames.
//

import CoreGraphics
import Foundation

struct FlutterAnycamIlluminationStats {
    let mean: Double
    let darkPixelRatio: Double
    let brightPixelRatio: Double
}

enum IlluminationUtil {
    private static let darkLum = 45
    private static let brightLum = 210

    private static func luminance(_ pixel: UnsafePointer<UInt8>) -> Int {
        let b = Int(pixel[0])
        let g = Int(pixel[1])
        let r = Int(pixel[2])
        return (77 * r + 150 * g + 29 * b) >> 8
    }

    static func stats(
        bgra: Data,
        width: Int,
        height: Int,
        roi: CGRect?,
        sampleStep: Int
    ) -> FlutterAnycamIlluminationStats {
        guard width >= 1, height >= 1, bgra.count >= width * height * 4 else {
            return FlutterAnycamIlluminationStats(mean: 0, darkPixelRatio: 0, brightPixelRatio: 0)
        }

        let step = max(1, sampleStep)
        var roiX = 0
        var roiY = 0
        var roiW = width
        var roiH = height

        if let roi = roi, roi.width > 0, roi.height > 0 {
            roiX = max(0, min(width - 1, Int(floor(roi.minX))))
            roiY = max(0, min(height - 1, Int(floor(roi.minY))))
            let roiRight = min(width, Int(ceil(roi.maxX)))
            let roiBottom = min(height, Int(ceil(roi.maxY)))
            roiW = max(0, roiRight - roiX)
            roiH = max(0, roiBottom - roiY)
        }

        guard roiW >= 1, roiH >= 1 else {
            return FlutterAnycamIlluminationStats(mean: 0, darkPixelRatio: 0, brightPixelRatio: 0)
        }

        let xEnd = roiX + roiW
        let yEnd = roiY + roiH
        let stride = width * 4

        var sum = 0.0
        var darkCount = 0
        var brightCount = 0
        var count = 0

        bgra.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }

            var y = roiY
            while y < yEnd {
                var x = roiX
                while x < xEnd {
                    let lum = luminance(base + y * stride + x * 4)
                    sum += Double(lum)
                    if lum < darkLum { darkCount += 1 }
                    if lum > brightLum { brightCount += 1 }
                    count += 1
                    x += step
                }
                y += step
            }
        }

        guard count > 0 else {
            return FlutterAnycamIlluminationStats(mean: 0, darkPixelRatio: 0, brightPixelRatio: 0)
        }

        let total = Double(count)
        return FlutterAnycamIlluminationStats(
            mean: sum / total,
            darkPixelRatio: Double(darkCount) / total,
            brightPixelRatio: Double(brightCount) / total
        )
    }
}
