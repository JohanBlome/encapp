//
//  CodecHelper.swift
//  Encapp
//
//  Created by Johan Blome on 12/2/22.
//

import Foundation
import VideoToolbox
import AVFoundation

func logVTSessionProperties(statistics: Statistics, compSession: VTSession) {
    var dict: CFDictionary?
    var status = VTSessionCopySupportedPropertyDictionary(compSession, supportedPropertyDictionaryOut: &dict)
    if status == noErr {
        let nsdict = dict! as NSDictionary
        for (key, value) in nsdict {
            log.debug("Supported Key: \(key)")
            let d = value as? NSDictionary
            if d?["PropertyType"] != nil {
                if d?["PropertyType"]! as? String == "Number"{
                    var propval: CFNumber!
                    status = VTSessionCopyProperty(compSession, key: key as! CFString, allocator: nil, valueOut: &propval)
                    if status == noErr{
                        statistics.addProp(name: key as! String, val: "\(propval!)")
                    }
                    
                } else if d?["PropertyType"]! as? String == "Boolean"{
                    var propval: CFBoolean!
                    status = VTSessionCopyProperty(compSession, key: key as! CFString, allocator: nil, valueOut: &propval)
                    if status == noErr && propval != nil{
                        let val = CFBooleanGetValue(propval!) ? "true" : "false"
                        statistics.addProp(name: key as! String, val: val)
                    }
                }  else if d?["PropertyType"]! as? String == "Enumeration"{
                    var propval: CFDictionary!
                    status = VTSessionCopyProperty(compSession, key: key as! CFString, allocator: nil, valueOut: &propval)
                    if status == noErr && propval != nil{
                        statistics.addProp(name: key as! String, val: "\(String(describing: propval))")
                    }
                } else {
                    log.error("Unhandled property: \(key) - \(String(describing: d))")
                }
               
            }
        }
            
    } else {
        log.error("Failed to get props: \(status)")
    }
}


func setVTEncodingSessionProperties(definition: Test, compSession: VTCompressionSession) {
    var status = Int32(0)
    
    
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
    if status != 0 {
        log.error("failed to set no b frames, status: \(status)")
    } else {
        log.info("Succesfully disallowed framereordering")
    }
    

    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
    if status != 0 {
        log.error("failed to set realtime, status: \(status)")
    } else {
        log.info("Succesfully set realtime property")
    }
    
    
    //TODO: Check this specific feature
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, value: kCFBooleanFalse)
    if status != 0 {
        log.error("failed to set speed over quality, status: \(status)")
    } else {
        log.info("Succesfully set speed over quality quality")
    }
    
    
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_MaxFrameDelayCount, value: 1 as CFTypeRef)
    if status != 0 {
        log.error("failed to singel frame delay, status: \(status)")
    } else {
        log.info("Succesfully set max 1 frame delay")
    }
    
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel as CFString)
    if status != 0 {
        log.error("failed to set profile, status: \(status)")
    } else {
        log.info("Succesfully set profile")
    }
    
   /* if #available(iOS 16.0, *) {
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_ConstantBitRate, value: bitrate as CFTypeRef)
    } else {
        // Fallback on earlier versions
    }
    if status != 0 {
        log.error("failed to set bitrate, status: \(status)")
    } else {
        log.info("Succesfully set average rate: \(bitrate)")
    }*/

    var framerate = Float(definition.configure.framerate)
    if framerate == 0 {
        framerate = Float(definition.input.framerate)
    }
    if framerate == 0 {
        framerate = 30.0
    }
    
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: framerate as CFTypeRef)
    if status != 0 {
        log.error("failed to set framerate, status: \(status)")
    } else {
        log.info("Succesfully set expected framerate: \(framerate)")
    }
    
    var keyframeIntervalSec = Int(definition.configure.iFrameInterval) //defined in secs
    if keyframeIntervalSec == 0 {
        keyframeIntervalSec = 10
    }
    

    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: keyframeIntervalSec as CFTypeRef)
    if status != 0 {
        log.error("failed to set key frame interval duration, status: \(status)")
    } else {
        log.info("Succesfully set key frame interval duration: \(keyframeIntervalSec)")
    }
    
    let keyframeInterval = Float(keyframeIntervalSec) * framerate
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: (keyframeInterval) as CFTypeRef)
    if status != 0 {
        log.error("failed to set key frame interval, status: \(status)")
    } else {
        log.info("Succesfully set key frame interval: \(keyframeInterval)")
    }
    
    var bitrate = magnitudeToInt(stringValue: definition.configure.bitrate)
    if bitrate == 0 {
        bitrate = 500000 //500kbps
    }
    
    
    setBitrate(compSession: compSession, bps: bitrate, cbr: definition.configure.bitrateMode == Configure.BitrateMode.cbr)
    
   
        // temporal
    //if definition.configure.hasTsSchema {
        // If temporal layers are turned off we end up with only I frames. Default is on.
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_AllowTemporalCompression, value: kCFBooleanTrue)
        if status != 0 {
            log.error("failed to enable temporal layers, status: \(status)")
        } else {
            log.info("Succesfully enabled temporal layers")
        }
        /*
        let bitrateRatio = 0.1
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_BaseLayerBitRateFraction, value: bitrateRatio as CFTypeRef)
        if status != 0 {
            log.error("failed to set baselayer bitrate fraction, status: \(status)")
        } else {
            log.info("Succesfully set baselayer bitrate fraction: \(bitrateRatio)")
        }
    
        let baseLayerFramerate = 1.0
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_BaseLayerFrameRate , value: baseLayerFramerate as CFTypeRef)
        if status != 0 {
            log.error("failed to set baselayer framerate, status: \(status)")
        } else {
            log.info("Succesfully set baselayer framerate: \(baseLayerFramerate)")
        }

    */
        
/*  } else {
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_AllowTemporalCompression, value: kCFBooleanFalse)
        if status != 0 {
            log.error("failed to disable temporal layers, status: \(status)")
        } else {
            log.info("Succesfully disabled temporal layers")
        }
    }*/
    
   /* status = VTSessionSetProperty(compSession, key: kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder, value: kCFBooleanTrue)
    if status != 0 {
        print("failed to set require hw acceleration, status: \(status)")
    }*/
    //TODO: Check this
    //kVTCompressionPropertyKey_MaximizePowerEfficiency
    //TODO: level
    //kVTProfileLevel_H264_Baseline_AutoLevel
    //TODO: color handling
    //TODO: how is this handled with the choice of encoder?
}

func setBitrate(compSession: VTCompressionSession, bps: Int, cbr: Bool) {
   var status = Int32(0)
   if cbr {
        if #available(iOS 16.0, *) {
#if os(iOS)
            status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_ConstantBitRate, value: bps as CFNumber)
#endif
        } else {
            // Fallback on earlier versions
            log.error("No cbr rate control for pre ios v16, use average (which is broken)")
            status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFNumber)
        }
    } else {
        status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFNumber)
    }
    if status != 0 {
        log.error("failed to set bitrate, status: \(status)")
    } else {
        log.debug("Successfully set new bitrate: \(bps) bps, cbr: \(cbr)")
    }
    // Allow how much overshoot?
    let overshoot = 1.0
    let intergrationTimeSec = 3.0
    let limBytes = intergrationTimeSec * Double(bps) * overshoot / 8.0
    let limits = [
        Int64(limBytes) as CFNumber,
        intergrationTimeSec as CFNumber,
    ]
    // This can be overriden by using dynamic settings (which is set after bitrate for a specific frame)
    status = VTSessionSetProperty(compSession, key: kVTCompressionPropertyKey_DataRateLimits, value: limits as CFArray)
    if status != 0 {
        log.error("failed to set bitrate, status: \(status)")
    } else {
        log.debug("Successfully set new bitrate: \(bps) bps cbr: \(cbr) - \(limits)")
    }
}

extension String {
    func fileExtension() -> String {
        return URL(fileURLWithPath: self).pathExtension
    }
}

func isKnownEncodingExtension(videopath: String) -> Bool {
    let VIDEO_ENCODED_EXTENSIONS = ["mp4", "webm", "mkv", "mov"]
    if VIDEO_ENCODED_EXTENSIONS.contains(videopath.fileExtension()) {
        return true
    } else {
        return false
    }
    
}

// kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder
//kVTCompressionPropertyKey_MaxAllowedFrameQP
//kVTCompressionPropertyKey_MinAllowedFrameQP
