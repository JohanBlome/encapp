//
//  Decoder.swift
//  Encapp
//
//  Created by Johan Blome on 1/6/23.
//


import Foundation
import VideoToolbox
import AVFoundation

extension AVAssetTrack {
    var mediaFormat: String {
        var format = ""
        let descriptions = self.formatDescriptions as! [CMFormatDescription]
        for (index, formatDesc) in descriptions.enumerated() {
            // Get a string representation of the media type.
            let type =
            CMFormatDescriptionGetMediaType(formatDesc).description
            // Get a string representation of the media subtype.
            let subType =
                CMFormatDescriptionGetMediaSubType(formatDesc).description
            // Format the string as type/subType, such as vide/avc1 or soun/aac.
            format += "\(type)/\(subType)"
            // Comma-separate if there's more than one format description.
            if index < descriptions.count - 1 {
                format += ","
            }
        }
        return format
    }
}

extension FourCharCode {
    // Create a string representation of a FourCC.
    func toString() -> String {
        let bytes: [CChar] = [
            CChar((self >> 24) & 0xff),
            CChar((self >> 16) & 0xff),
            CChar((self >> 8) & 0xff),
            CChar(self & 0xff),
            0
        ]
        let result = String(cString: bytes)
        let characterSet = CharacterSet.whitespaces
        return result.trimmingCharacters(in: characterSet)
    }
}



class Decoder {
    var definition:Test
    var statistics: Statistics!
    var trackOutput: AVAssetReaderOutput!
    var compSession: VTDecompressionSession!
    var formatDescription: CMFormatDescription!
    var lastTimeMs = 0 as Int64
    var currentTimeSec = 0 as Double
    var inputDone = false
    init(test: Test){
        self.definition = test
    }



    func Decode() -> String {
        statistics = Statistics(description: "decoder", test: definition);
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            statistics = Statistics(description: "decoder", test: definition);
            log.info("Encode, current test definition = \n\(definition)")

            // Filehandling
            let fileURL = dir.appendingPathComponent(definition.input.filepath)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                log.info("Input media file: \(fileURL.path)")
            } else {
                log.error("Media file: \(fileURL.path) doe not exist")
                return "Error: no media file"
            }

            let source = try AVAsset(url: fileURL)
            let inputReader = try? AVAssetReader(asset:source)

            let semaphore = DispatchSemaphore(value: 0)
            //Assume singel track
            //var tracks: AVAssetTrack?

            Task {
                let tracks =  try await source.load(.tracks)
                trackOutput = AVAssetReaderTrackOutput(track: tracks[0], outputSettings: nil)
                semaphore.signal()
            }
            semaphore.wait()

            print("Continue")
            inputReader?.add(trackOutput)
            inputReader?.startReading()

            // Callback
            let decodeCallback: VTDecompressionOutputHandler = { status, infoFlags, imageBuffer, presentationTs, duration in
                self.statistics.stopDeccoding(pts: Int64(presentationTs.seconds * 1000000.0))
                self.currentTimeSec = presentationTs.seconds
            }

            var frameNum = 0 as UInt32
            var inputFrameCounter = 0
            //TODO:
            var currentLoop = 1

            while (!inputDone) {
                if (inputFrameCounter % 100 == 0) {
                    log.info("""
                            \(definition.common.id) - Decoder:
                       input frames: \(inputFrameCounter) current loop: \(currentLoop) current time: \(currentTimeSec)
                       """)
                    if doneReading(test: definition, stream: nil, frame: inputFrameCounter, time: Float(currentTimeSec), loop: false) {
                        self.inputDone = true
                    }
                }
                let sampleBuffer = trackOutput.copyNextSampleBuffer()
                if sampleBuffer == nil {
                    inputDone = true
                } else {
                    frameNum += 1
                    var decodeInfoFlags = VTDecodeFrameFlags()
                    var infoFlags = VTDecodeInfoFlags(rawValue: frameNum)
                    if compSession == nil && sampleBuffer != nil {
                        setupDecoder(sampleBuffer: sampleBuffer!)
                    }
                    let frameDurationMs = (sampleBuffer?.duration.seconds)! * 1000.0
                    if compSession != nil && sampleBuffer != nil {
                        if definition.input.realtime {
                            lastTimeMs = sleepUntilNextFrame(lastTimeMs: lastTimeMs, frameDurationMs: frameDurationMs)
                        }
                        inputFrameCounter += 1
                        guard (sampleBuffer?.presentationTimeStamp.seconds)!.isFinite else {
                            log.debug("Decoder time is not finite, cannot decompress. Most likely end of stream.")
                            return ""
                        }
                        statistics.startDecoding(pts: Int64((sampleBuffer?.presentationTimeStamp.seconds)! * 1000000.0))
                        let status = VTDecompressionSessionDecodeFrame(compSession,
                                                                       sampleBuffer: sampleBuffer!,
                                                                       flags: decodeInfoFlags,
                                                                       infoFlagsOut: &infoFlags,
                                                                       outputHandler: decodeCallback)
                    } else {
                        log.error("compsession or samplebuffer is nil")
                    }
                }
            }
        }

        statistics.stop()
        return ""
    }



    func setupDecoder(sampleBuffer: CMSampleBuffer) {
        if sampleBuffer.formatDescription == nil {
            log.error("no format descritpion")
            return
        }
        let imagerBufferAttributes = [
            kCVPixelBufferOpenGLCompatibilityKey: NSNumber(true),
        ]
        let compressionSessionOut = UnsafeMutablePointer<VTDecompressionSession?>.allocate(capacity: 1)
       /* let decoderSpecification = [
            kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: NSString(string: "true"),
       ]*/
        // Create session
        log.info("Create decoder session with type: \(sampleBuffer.formatDescription!) - \(statistics.encoderName)")
        statistics.start()
        var status = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault,
                                                            formatDescription: sampleBuffer.formatDescription!,
                                                            decoderSpecification: nil,
                                                            imageBufferAttributes: imagerBufferAttributes as CFDictionary?,
                                                            outputCallback: nil,
                                                  decompressionSessionOut: compressionSessionOut)

        if (status != noErr) {
        log.error("Failed to create encoder session, \(status) ")
            log.debug("Failed to create encoder session, \(status)")
            return
        }

        compSession = compressionSessionOut.pointee
        log.info("comp: \(compSession.debugDescription)")
        var gpu: CFBoolean!
        status = VTSessionCopyProperty(compSession, key: kVTDecompressionPropertyKey_UsingGPURegistryID, allocator: nil, valueOut: &gpu)
        if status != 0 {
            log.error("failed to check gpu statis, status: \(status)")
        } else {
            log.info("Gpu status: \(gpu)")
        }


        logVTSessionProperties(statistics: statistics, compSession: compSession)
    }
}
