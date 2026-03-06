//
//  ImageConverterUtil.swift
//  Pods
//
//  Created by Michael Lopes on 12/08/25.
//
import UIKit
import CoreGraphics
import Accelerate

class ImageConverterUtil: NSObject {
    /*static func convertBGRA8888ToJPEG(
     bgraData: Data,
     width: Int,
     height: Int,
     quality: CGFloat
     ) -> Data? {
     let bytesPerPixel = 4
     let bytesPerRow = width * bytesPerPixel
     let colorSpace = CGColorSpaceCreateDeviceRGB()
     
     
     let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
     
     guard let provider = CGDataProvider(data: bgraData as CFData) else { return nil }
     
     guard let cgImage = CGImage(
     width: width,
     height: height,
     bitsPerComponent: 8,
     bitsPerPixel: 32,
     bytesPerRow: bytesPerRow,
     space: colorSpace,
     bitmapInfo: bitmapInfo,
     provider: provider,
     decode: nil,
     shouldInterpolate: true,
     intent: .defaultIntent
     ) else {
     return nil
     }
     
     let uiImage = UIImage(cgImage: cgImage)
     return uiImage.jpegData(compressionQuality: quality)
     }*/
    
    
    // MARK: - Convert BGRA + Crop + Resize + Filter → JPEG
    static func convertBGRA8888ToJPEG(
        bgraData: Data,
        width: Int,
        height: Int,
        quality: CGFloat,
        filter: Int = 0,
        crop: [String: Any]? = nil
    ) -> Data? {
        let bytesPerPixel = 4
        var currentData = bgraData
        var currentWidth = width
        var currentHeight = height
        
        // ---------- CROP ----------
        if let cropMap = crop {
            var cWidth  = cropMap["width"]  as? Int ?? currentWidth
            var cHeight = cropMap["height"] as? Int ?? currentHeight
            var left    = cropMap["left"]   as? Int ?? 0
            var top     = cropMap["top"]    as? Int ?? 0
            
            /* rot = Int(rotation.rounded())
             if rot == 90 || rot == 270 {
             swap(&cWidth, &cHeight)
             swap(&left, &top)
             }*/
            
            // Clamp
            cWidth  = min(cWidth,  currentWidth)
            cHeight = min(cHeight, currentHeight)
            left    = min(left, currentWidth  - cWidth)
            top     = min(top,  currentHeight - cHeight)
            
            let srcRowBytes = currentWidth * bytesPerPixel
            let dstRowBytes = cWidth * bytesPerPixel
            var cropped = Data(count: cHeight * dstRowBytes)
            
            currentData.withUnsafeBytes { srcPtr in
                cropped.withUnsafeMutableBytes { dstPtr in
                    let src = srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                    let dst = dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                    for row in 0..<cHeight {
                        let srcOffset = (top + row) * srcRowBytes + left * bytesPerPixel
                        let dstOffset = row * dstRowBytes
                        memcpy(dst + dstOffset, src + srcOffset, cWidth * bytesPerPixel)
                    }
                }
            }
            
            currentData   = cropped
            currentWidth  = cWidth
            currentHeight = cHeight
            
            // ---------- RESIZE (dentro do crop) ----------
            /* if let resizeMap = cropMap["resize"] as? [String: Any] {
             var rWidth  = resizeMap["width"]  as? Int ?? currentWidth
             var rHeight = resizeMap["height"] as? Int ?? currentHeight
             
             /*   let rot2 = Int(rotation.rounded())
              if rot2 == 90 || rot2 == 270 {
              swap(&rWidth, &rHeight)
              }*/
             
             if(rWidth == -1 && rHeight == -1) {
             let minSize = min(currentWidth,  currentHeight);
             rWidth = minSize;
             rHeight = minSize;
             }
             
             rWidth  = min(rWidth,  currentWidth)
             rHeight = min(rHeight, currentHeight)
             
             let srcRowBytes = currentWidth * bytesPerPixel
             let dstRowBytes = rWidth * bytesPerPixel
             var resized = Data(count: rHeight * dstRowBytes)
             
             currentData.withUnsafeMutableBytes { srcPtr in
             resized.withUnsafeMutableBytes { dstPtr in
             var srcBuf = vImage_Buffer(
             data: srcPtr.baseAddress!,
             height: vImagePixelCount(currentHeight),
             width:  vImagePixelCount(currentWidth),
             rowBytes: srcRowBytes
             )
             var dstBuf = vImage_Buffer(
             data: dstPtr.baseAddress!,
             height: vImagePixelCount(rHeight),
             width:  vImagePixelCount(rWidth),
             rowBytes: dstRowBytes
             )
             vImageScale_ARGB8888(&srcBuf, &dstBuf, nil,
             vImage_Flags(kvImageHighQualityResampling))
             }
             }
             
             currentData   = resized
             currentWidth  = rWidth
             currentHeight = rHeight
             }*/
            
            // ---------- RESIZE (dentro do crop) ----------
            if let resizeMap = cropMap["resize"] as? [String: Any] {
                var rWidth  = resizeMap["width"]  as? Int ?? currentWidth
                var rHeight = resizeMap["height"] as? Int ?? currentHeight
                
                
                if(rWidth == -1 && rHeight == -1) {
                    let minSize = min(currentWidth,  currentHeight);
                    rWidth = minSize;
                    rHeight = minSize;
                }
                
                
                // Aspect fill: escala proporcional até preencher o target
                let scale = max(
                    Float(rWidth)  / Float(currentWidth),
                    Float(rHeight) / Float(currentHeight)
                )
                let scaledWidth  = Int(Float(currentWidth)  * scale)
                let scaledHeight = Int(Float(currentHeight) * scale)
                let scaledRowBytes = scaledWidth * bytesPerPixel
                
                var scaledData = Data(count: scaledHeight * scaledRowBytes)
                
                currentData.withUnsafeMutableBytes { srcPtr in
                    scaledData.withUnsafeMutableBytes { dstPtr in
                        var srcBuf = vImage_Buffer(
                            data: srcPtr.baseAddress!,
                            height: vImagePixelCount(currentHeight),
                            width:  vImagePixelCount(currentWidth),
                            rowBytes: currentWidth * bytesPerPixel
                        )
                        var dstBuf = vImage_Buffer(
                            data: dstPtr.baseAddress!,
                            height: vImagePixelCount(scaledHeight),
                            width:  vImagePixelCount(scaledWidth),
                            rowBytes: scaledRowBytes
                        )
                        vImageScale_ARGB8888(&srcBuf, &dstBuf, nil,
                                             vImage_Flags(kvImageHighQualityResampling))
                    }
                }
                
                // Crop central
                let startX = (scaledWidth  - rWidth)  / 2
                let startY = (scaledHeight - rHeight) / 2
                let dstRowBytes = rWidth * bytesPerPixel
                var cropped = Data(count: rHeight * dstRowBytes)
                
                scaledData.withUnsafeBytes { srcPtr in
                    cropped.withUnsafeMutableBytes { dstPtr in
                        let src = srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                        let dst = dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                        for row in 0..<rHeight {
                            let srcOffset = (startY + row) * scaledRowBytes + startX * bytesPerPixel
                            let dstOffset = row * dstRowBytes
                            memcpy(dst + dstOffset, src + srcOffset, rWidth * bytesPerPixel)
                        }
                    }
                }
                
                currentData   = cropped
                currentWidth  = rWidth
                currentHeight = rHeight
            }
        }
        
        
        
        // ---------- FILTER ----------
        if filter > 0 {
            currentData.withUnsafeMutableBytes { ptr in
                guard let base = ptr.baseAddress else { return }
                ImageConverterUtil.applyFilter(
                    baseAddress: base,
                    width:       currentWidth,
                    height:      currentHeight,
                    bytesPerRow: currentWidth * bytesPerPixel,
                    filter:      filter
                )
            }
        }
        
        // ---------- BGRA → JPEG ----------
        let bytesPerRow  = currentWidth * bytesPerPixel
        let colorSpace   = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo   = CGBitmapInfo(rawValue:
                                            CGImageAlphaInfo.premultipliedFirst.rawValue |
                                        CGBitmapInfo.byteOrder32Little.rawValue)
        
        guard let provider = CGDataProvider(data: currentData as CFData) else { return nil }
        guard let cgImage  = CGImage(
            width: currentWidth, height: currentHeight,
            bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: bytesPerRow,
            space: colorSpace, bitmapInfo: bitmapInfo,
            provider: provider, decode: nil,
            shouldInterpolate: true, intent: .defaultIntent
        ) else { return nil }
        
        return UIImage(cgImage: cgImage).jpegData(compressionQuality: quality)
    }
    
    static func resizeAspectFillAndCrop(
        pixelBuffer: CVPixelBuffer,
        targetWidth: Int,
        targetHeight: Int
    ) -> CVPixelBuffer? {
        
        guard CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA else {
            return nil
        }
        
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        
        guard let srcBaseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            return nil
        }
        
        let srcWidth = CVPixelBufferGetWidth(pixelBuffer)
        let srcHeight = CVPixelBufferGetHeight(pixelBuffer)
        let srcRowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer)
        
        // ---------- SCALE ----------
        
        let scale = max(
            Float(targetWidth) / Float(srcWidth),
            Float(targetHeight) / Float(srcHeight)
        )
        
        let resizedWidth = Int(Float(srcWidth) * scale)
        let resizedHeight = Int(Float(srcHeight) * scale)
        
        let bytesPerPixel = 4
        let resizedRowBytes = resizedWidth * bytesPerPixel
        
        var srcBuffer = vImage_Buffer(
            data: srcBaseAddress,
            height: vImagePixelCount(srcHeight),
            width: vImagePixelCount(srcWidth),
            rowBytes: srcRowBytes
        )
        
        var resizedData = Data(count: resizedHeight * resizedRowBytes)
        
        resizedData.withUnsafeMutableBytes { resizedPtr in
            
            var dstBuffer = vImage_Buffer(
                data: resizedPtr.baseAddress!,
                height: vImagePixelCount(resizedHeight),
                width: vImagePixelCount(resizedWidth),
                rowBytes: resizedRowBytes
            )
            
            vImageScale_ARGB8888(
                &srcBuffer,
                &dstBuffer,
                nil,
                vImage_Flags(kvImageHighQualityResampling)
            )
        }
        
        // ---------- CROP ----------
        
        let startX = (resizedWidth - targetWidth) / 2
        let startY = (resizedHeight - targetHeight) / 2
        
        var croppedPixelBuffer: CVPixelBuffer?
        
        let attrs: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
        ]
        
        CVPixelBufferCreate(
            kCFAllocatorDefault,
            targetWidth,
            targetHeight,
            kCVPixelFormatType_32BGRA,
            attrs as CFDictionary,
            &croppedPixelBuffer
        )
        
        guard let finalBuffer = croppedPixelBuffer else {
            return nil
        }
        
        CVPixelBufferLockBaseAddress(finalBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(finalBuffer, []) }
        
        guard let dstBaseAddress = CVPixelBufferGetBaseAddress(finalBuffer) else {
            return nil
        }
        
        let dstRowBytes = CVPixelBufferGetBytesPerRow(finalBuffer)
        
        resizedData.withUnsafeBytes { resizedPtr in
            
            let src = resizedPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
            let dst = dstBaseAddress.assumingMemoryBound(to: UInt8.self)
            
            for row in 0..<targetHeight {
                let srcOffset = (startY + row) * resizedRowBytes + startX * bytesPerPixel
                let dstOffset = row * dstRowBytes
                
                memcpy(dst + dstOffset,
                       src + srcOffset,
                       targetWidth * bytesPerPixel)
            }
        }
        
        return finalBuffer
    }
    
    
    static func applyFilter( baseAddress: UnsafeMutableRawPointer,
                             width: Int,
                             height: Int,
                             bytesPerRow: Int,
                             filter: Int) {
        if(filter > 0) {
            var contrast : Float? = nil;
            
            switch (filter) {
            case 2:
                contrast = 1.2;
                break;
            case 3:
                contrast = 1.35;
                break;
            case 4:
                contrast = 1.60;
                break;
            default:
                contrast = nil;
                break;
            }
            
            ImageConverterUtil.processGrayscale(baseAddress: baseAddress, width: width, height: height, bytesPerRow: bytesPerRow, applyGrayscale: true, contrast: contrast);
        }
    }
    
    static func processGrayscale(
        baseAddress: UnsafeMutableRawPointer,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        applyGrayscale: Bool,
        contrast: Float? // nil = não aplicar contraste
    ) {
        let buffer = baseAddress.assumingMemoryBound(to: UInt8.self)
        
        for y in 0..<height {
            let row = buffer + y * bytesPerRow
            
            for x in 0..<width {
                let pixel = row + x * 4
                
                var gray: Float
                
                if applyGrayscale {
                    // BGRA
                    let b = Float(pixel[0])
                    let g = Float(pixel[1])
                    let r = Float(pixel[2])
                    
                    gray = 0.299 * r + 0.587 * g + 0.114 * b
                } else {
                    gray = Float(pixel[1])
                }
                
                if let c = contrast {
                    gray = (gray - 128.0) * c + 128.0
                }
                
                if gray < 0 { gray = 0 }
                if gray > 255 { gray = 255 }
                
                let final = UInt8(gray)
                
                // Se aplicou grayscale ou contraste, escreve nos 3 canais
                if applyGrayscale || contrast != nil {
                    pixel[0] = final
                    pixel[1] = final
                    pixel[2] = final
                }
            }
        }
    }
}
