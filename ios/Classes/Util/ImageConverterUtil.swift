//
//  ImageConverterUtil.swift
//  Pods
//
//  Created by Michael Lopes on 12/08/25.
//
import UIKit
import CoreGraphics

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
}
