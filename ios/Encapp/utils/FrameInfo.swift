//
//  FrameInfo.swift
//  Encapp
//
//  Created by Johan Blome on 12/1/22.
//

import Foundation
class FrameInfo:Equatable {
    static func == (lhs: FrameInfo, rhs: FrameInfo) -> Bool {
        return lhs.pts == rhs.pts && lhs.originalFrame == rhs.originalFrame
    }
    

    var pts: Int64 = 0
    var proctime: Int64 = -1
    var size: Int64! = 0
    var startTime: Int64 = -1
    var stopTime: Int64 = -1
    var keyFrame = false
    var originalFrame: Int = -1
    
    init(pts: Int64) {
        self.pts = pts
    }
    
    init(pts: Int64, originalFrame: Int) {
        self.pts = pts
        self.originalFrame = originalFrame
    }
    
    func getPts() -> Int64 {
        return pts
    }
    
    func setSize(size: Int64)  {
        self.size = size
    }
    
    func getSize() -> Int64 {
        return size
    }
    
    func getStartTime() -> Int64 {
        return startTime
    }
    
    func getStopTime() -> Int64 {
        return stopTime
    }
    
    func setKeyFrame(isKeyFrame: Bool)  {
        self.keyFrame = isKeyFrame
    }
    
    func isKeyFrame() -> Bool {
        return keyFrame
    }
    
    func setOriginalFrame(originalFrame: Int)  {
        self.originalFrame = originalFrame
    }
    
    func getOriginalFrame() -> Int {
        return originalFrame
    }
    
    func start() {
        startTime = timeStampNs()
    }
    
    func stop() {
        stopTime = timeStampNs()
    }
    
    func getProcessingTime() -> Int64 {
        return stopTime - startTime;
    }
}
