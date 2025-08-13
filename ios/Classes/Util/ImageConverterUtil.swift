//
//  ImageConverterUtil.swift
//  Pods
//
//  Created by Michael Lopes on 12/08/25.
//
import UIKit
import CoreGraphics

class ImageConverterUtil: NSObject {
  /*  static func convertBGRA8888ToJPEG(bgraData: Data, width: Int, height: Int, quality: CGFloat) -> Data? {
        let bytesPerPixel = 4
        let bytesPerRow = width * bytesPerPixel
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        
        let bitmapInfo: CGBitmapInfo = [
            .byteOrder32Little,
            CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue)
        ]
        
        guard let providerRef = CGDataProvider(data: bgraData as CFData) else {
            return nil
        }
        
        guard let cgImage = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo,
            provider: providerRef,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        ) else {
            return nil
        }
        
        let uiImage = UIImage(cgImage: cgImage)
        guard let jpegData = uiImage.jpegData(compressionQuality: quality) else {
            return nil
        }
        
        return jpegData
    }*/
    
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
          
        let uiImage = UIImage(cgImage: cgImage, scale: 1.0, orientation: .upMirrored)
          return uiImage.jpegData(compressionQuality: 1)
        
       /* let isPortrait = height > width
        
        let bitmapInfo: CGBitmapInfo = [
            .byteOrder32Little,
            CGBitmapInfo(rawValue: CGImageAlphaInfo.first.rawValue) // BGRA (não premultiplicado)
        ]
        
        guard let providerRef = CGDataProvider(data: bgraData as CFData) else {
            return nil
        }
        
        guard let cgImage = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo,
            provider: providerRef,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        ) else {
            return nil
        }

        // Ajuste a orientação da UIImage com base em `isPortrait`
        /*let imageOrientation: UIImage.Orientation = isPortrait ? .up : .right*/
        let uiImage = UIImage(cgImage: cgImage, scale: 1.0, orientation: .down)
        
        // Converta para JPEG
        return uiImage.jpegData(compressionQuality: quality)*/
    }


}
