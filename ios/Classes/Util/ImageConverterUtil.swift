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
    static func convertBGRA8888ToJPEG(
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
