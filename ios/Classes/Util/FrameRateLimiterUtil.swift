//
//  FrameRateLimiterUtil.swift
//  Pods
//
//  Created by Michael Lopes on 08/08/25.
//
import Foundation


class FrameRateLimiterUtil<T> {
    private let frameInterval: TimeInterval
    private var lastAnalyzedTime: TimeInterval = 0
    
    // Closures para tratamento de frames
    private let onFrameLimited: (T) -> Void
    private let onFrameSkipped: (T) -> Void
    
    init(targetFps: Int,
         onFrameLimited: @escaping (T) -> Void,
         onFrameSkipped: @escaping (T) -> Void = { _ in }) {
        precondition(targetFps > 0, "FPS precisa ser > 0")
        self.frameInterval = 1.0 / Double(targetFps)
        self.onFrameLimited = onFrameLimited
        self.onFrameSkipped = onFrameSkipped
    }
    
    func processFrame(_ data: T) {
        let currentTime = CACurrentMediaTime()
        if currentTime - lastAnalyzedTime >= frameInterval {
            lastAnalyzedTime = currentTime
            onFrameLimited(data)
        } else {
            onFrameSkipped(data)
        }
    }
}
