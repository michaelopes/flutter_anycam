//
//  BlurUtil.swift
//  flutter_anycam
//
//  Laplacian variance blur score on BGRA8888 frames.
//

import CoreGraphics
import Foundation

enum BlurUtil {
    private static func luminance(_ pixel: UnsafePointer<UInt8>) -> Int {
        let b = Int(pixel[0])
        let g = Int(pixel[1])
        let r = Int(pixel[2])
        return (77 * r + 150 * g + 29 * b) >> 8
    }

    static func laplacianVariance(
        bgra: Data,
        width: Int,
        height: Int,
        roi: CGRect?,
        sampleStep: Int
    ) -> Double {
        guard width >= 3, height >= 3, bgra.count >= width * height * 4 else {
            return 0
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

        guard roiW >= 3, roiH >= 3 else { return 0 }

        let xStart = roiX + 1
        let yStart = roiY + 1
        let xEnd = roiX + roiW - 1
        let yEnd = roiY + roiH - 1
        let stride = width * 4

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        bgra.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }

            func lumAt(_ x: Int, _ y: Int) -> Int {
                luminance(base + y * stride + x * 4)
            }

            var y = yStart
            while y < yEnd {
                var x = xStart
                while x < xEnd {
                    let c = lumAt(x, y)
                    let l = lumAt(x - 1, y)
                    let r = lumAt(x + 1, y)
                    let t = lumAt(x, y - 1)
                    let b = lumAt(x, y + 1)
                    let lap = 4 * c - l - r - t - b
                    sum += Double(lap)
                    sumSq += Double(lap * lap)
                    count += 1
                    x += step
                }
                y += step
            }
        }

        guard count > 0 else { return 0 }
        let mean = sum / Double(count)
        return (sumSq / Double(count)) - (mean * mean)
    }
}
