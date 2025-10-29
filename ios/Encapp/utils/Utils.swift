//
//  Utils.swift
//  Encapp
//
//  Created by Johan Blome on 11/23/22.
//

import Foundation

func splitX(text: String)-> [Int] {

    let split = (text.lowercased()).components(separatedBy: "x")
    print("\(text) -> split: \(split)")
    let w = split[0]
    let h = split[1]

    let output = [w,h]
    print("\(output)")
    return [Int(w) ?? 0, Int(h) ?? 0]
}


func timeStampNs() -> Int64 {
    var time = timespec()
    clock_gettime(CLOCK_MONOTONIC_RAW, &time)
    let ret = Int64(time.tv_sec * 1000_000_000 + time.tv_nsec)
    //log.info("time: \(ret),time.tv_sec \(time.tv_sec) - nsec: \(time.tv_nsec)")
    return ret
}


var cal = Calendar(identifier: .gregorian)
func timestampDateTime() -> String {
    let d = Date()
    var time = timespec()
    clock_gettime(CLOCK_MONOTONIC_RAW, &time)
    return "\(cal.component(.year, from: d))-\(cal.component(.month, from: d))-\(cal.component(.day, from: d)) \(cal.component(.hour, from: d)):\(cal.component(.minute, from: d)):\(cal.component(.second, from: d)).\(Int(time.tv_nsec/1000000))"
}


func magnitudeToInt(stringValue: String)-> Int {
    //TODO: what version does what?
    var text = stringValue.replacingOccurrences(of: " ", with: "")
    if (text.hasSuffix( "bps")) {
        text = String(text.dropLast(3))
    }

    var val = 0 as Int
    if (text.count == 0) {
        return 0;
    } else if (text.hasSuffix("k")) {
        val = Int(text.dropLast(1))! * 1000;
    } else if (text.hasSuffix("M")) {
        val = Int(text.dropLast(1))! * 1000000;
    } else {
        val = Int(text)!;
    }

    return val;
}


func computePresentationTimeUsec(frameIndex: Int, frameTimeUsec: Float, offset: Int64) -> Int64{
    return offset + Int64(Float(frameIndex) * frameTimeUsec)
}

func calculateFrameTimingUsec(frameRate: Float) -> Float{
    let val = 1000000.0 / frameRate;
    print("Calc timeing: \(val), \(frameRate)")
    return val
}

func dropFromDynamicFramerate(frame: Int, keepInterval: Float) -> Bool {
    let currentFrameNbr = Int(Float(frame) / keepInterval);
    let nextFrameNbr = Int (Float ((frame + 1)) / keepInterval);
    return currentFrameNbr == nextFrameNbr;
}




func doneReading(test: Test, stream: InputStream?, frame: Int, time: Float, loop: Bool) -> Bool {
    if (loop && !test.input.hasStoptimeSec && !test.input.hasPlayoutFrames) {
        // 1. stop the reading when reaching end of input file
        return true
    }
    if (test.input.hasPlayoutFrames && test.input.playoutFrames > 0) {
        // 2. stop the reader based on explicit playout frames parameter:
        // stop if we reached the explicit playout frames
        if (frame >= test.input.playoutFrames) {
            return true
        }
    }
    if (test.input.hasStoptimeSec && test.input.stoptimeSec > 0) {
        // 3. stop the reader based on explicit stoptime parameter:
        // stop if we reached the explicit stoptime
        if (time >= test.input.stoptimeSec) {
            print("\(test.common.id) - Stoptime reached: \(time) - \(test.input.stoptimeSec)");
            return true
        }
    }
    // 4. stop the reader in non-loop mode:
    // stop when the file is empty
    //if ((!loop && is != nil)) {
    if stream != nil && !loop && stream!.streamStatus == InputStream.Status.closed {
        return true
    }
    // do not stop the reader
    return false

}

// Swift does not allow a name loopup (which Android and python does)
func pixFmtToString(format:  PixFmt) -> String{
    switch format {
    case PixFmt.nv12:
        return "nv12"
    case PixFmt.rgba:
        return "rgba"
    case PixFmt.yuv420P:
        return "yuv420p"
    case PixFmt.nv21:
        return "nv21"
    default:
        return "unknown"
    }
}

func sleepUntilNextFrame(lastTimeMs: Int64, frameDurationMs: Double) -> Int64 {
    let now = timeStampNs() / 1000000
    var updatedLastTime = lastTimeMs
    if updatedLastTime <= 0 {
        updatedLastTime = now
        return updatedLastTime;
    }
    // This sleep is controlled bu the input framerate
    var sleepTimeMs = frameDurationMs - Double(now - updatedLastTime)
    if sleepTimeMs < 0 {
        sleepTimeMs = 0
    }
    if sleepTimeMs > 0 {
        usleep(UInt32(sleepTimeMs * 1000))
    }
    updatedLastTime = timeStampNs()/1000000
    return updatedLastTime
}

