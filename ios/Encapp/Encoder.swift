//
//  Encoder.swift
//  Encapp
//
//  Created by Johan Blome on 11/23/22.
//

import Foundation
import VideoToolbox
import AVFoundation

class Encoder {
    var compSession: VTCompressionSession!
    var encodedFile: FileHandle!
    var statistics: Statistics!
    var inputFrameCounter = 0
    var framesAdded = 0
    var writtenFrameTime = 0
    var definition:Test
    var lastTimeMs = -1 as Int64
    var outputDone = false
    var currentTimeSec = 0 as Float
    var frameRate = -1 as Float


    var frameDurationCMTime: CMTime!
    var frameDurationMs = -1 as Float
    var frameDurationUsec = -1 as Float
    var inputFrameDurationUsec = 0 as Float
    var inputFrameDurationMs = 0 as Float

    //This is the input frame rate,
    //used to figure out relations to the output frquecny
    var inputFrameRate = -1 as Float
    var outputFrameRate = -1 as Float

    //
    var pts = 0
    var lastPts: CMTime!
    var keepInterval = 1.0 as Float
    var dropNext = false
    var skipped = 0
    var frameDurationSec: Float!
    var scale: Int!
    var currentLoop = 1
    var inputDone = false
    // setting constant bitrate seems to be totally broken - set average
    var isCbr = false

    //
    var outputWriterInput: AVAssetWriterInput!

    init(test: Test){
        self.definition = test
    }



    func Encode() throws  -> String {
        statistics = Statistics(description: "raw encoder", test: definition);
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            log.info("Encode, current test definition = \n\(definition)")

            // Check input
            let resolution = splitX(text: definition.input.resolution)
            let sourceWidth = resolution[0]
            let sourceHeight = resolution[1]
            // TODO: it seems the encoder is expecting aligned planes
            let width = Int((resolution[0] >> 1) << 1)
            let height = Int((resolution[1] >> 1) << 1)
            inputFrameRate = (definition.input.hasFramerate) ? definition.input.framerate: 30.0
            outputFrameRate = (definition.configure.hasFramerate) ? definition.configure.framerate: inputFrameRate

            if inputFrameRate <= 0 {
                inputFrameRate = 30.0
            }
            if outputFrameRate <= 0  {
                outputFrameRate = 30.0
            }
            keepInterval = inputFrameRate / outputFrameRate;
            frameDurationUsec = calculateFrameTimingUsec(frameRate: outputFrameRate);
            inputFrameDurationUsec = calculateFrameTimingUsec(frameRate: inputFrameRate);
            inputFrameDurationMs = Float(frameDurationUsec) / 1000.0
            scale = 1000_000 //should be 90000?
            frameDurationUsec =  calculateFrameTimingUsec(frameRate: outputFrameRate);
            frameDurationMs = Float(frameDurationUsec) / 1000.0
            frameDurationSec = Float(frameDurationUsec) / 1000_000.0
            frameDurationCMTime = CMTime.init(value: Int64(1.0/30.0 * Double(scale)), timescale: CMTimeScale(scale))

            // Codec type
            if !definition.configure.hasCodec {
                log.error("No codec defined")
                return ""
            }
            let props = ListProps()
            let codecType = props.lookupCodectType(name: definition.configure.codec)
            let codecId = props.getCodecIdFromType(encoderType: codecType)
            let codecName = props.getCodecNameFromType(encoderType: codecType)
            statistics.setEncoderName(encoderName: codecName)
            //output
            let imageBufferAttributes = [
                kCVPixelBufferWidthKey: NSNumber(value: width),
                kCVPixelBufferHeightKey: NSNumber(value: height),
                kCVPixelBufferPixelFormatTypeKey: NSNumber(value: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
            ]

            let compressionSessionOut = UnsafeMutablePointer<VTCompressionSession?>.allocate(capacity: 1)


            // Callback
            let encodeCallback: VTCompressionOutputCallback = { outputCallbackRefCon, sourceFrameRefCon, status, infoFlags, sampleBuffer in
                let encoder: Encoder = Unmanaged<Encoder>.fromOpaque(sourceFrameRefCon!).takeUnretainedValue()
                if sampleBuffer != nil && CMSampleBufferDataIsReady(sampleBuffer!) {
                    encoder.writeData(sampleBuffer: sampleBuffer!, infoFlags: infoFlags)
                } else {
                    if (infoFlags.rawValue == VTEncodeInfoFlags.frameDropped.rawValue) {
                        log.debug("Encoder dropped frame")
                    } else if (infoFlags.rawValue == VTEncodeInfoFlags.asynchronous.rawValue) {
                        log.debug("Aynchronous frame")
                    } else {
                        encoder.fail(cause: "Sample buffer is not ok, \(sampleBuffer.debugDescription), \(status) ")
                    }
                }
            }

            log.debug("codecId: \(codecId)")
            let encoderSpecification = [
                kVTVideoEncoderSpecification_EncoderID: NSString(string: codecId),
                // The next property will disable any settings done at a later stage when it comes to frame order
                // frame drops, latency etc.
                //kVTVideoEncoderSpecification_EnableLowLatencyRateControl: NSString(string: "true"),
                //TODO: profiles should be added.
                //kVTCompressionPropertyKey_ProfileLevel: NSString(string: kVTProfileLevel_H264_High_AutoLevel),
            ]
            // Create session
            log.info("Create encoder session with type: \(codecType) - \(statistics.encoderName)")
            var status = VTCompressionSessionCreate(allocator: kCFAllocatorDefault,
                                                    width: Int32(width),
                                                    height: Int32(height),
                                                    codecType: codecType, //kCMVideoCodecType_H264,
                                                    encoderSpecification: encoderSpecification as CFDictionary?,
                                                    imageBufferAttributes: imageBufferAttributes as CFDictionary?,
                                                    compressedDataAllocator: kCFAllocatorDefault,
                                                    outputCallback: encodeCallback,
                                                    refcon: Unmanaged.passUnretained(self).toOpaque(),
                                                    compressionSessionOut: compressionSessionOut)

            if (status != noErr) {
                log.error("Failed to create encoder session, \(status) ")
                return "Failed to create encoder session, \(status)"
            }
            compSession = compressionSessionOut.pointee

            // Configure encoder
            setVTEncodingSessionProperties(definition: definition, compSession: compSession)
            status = VTCompressionSessionPrepareToEncodeFrames(compSession)
            if status != 0 {
                log.error("failed prepare for encode, status: \(status)")

            }

            logVTSessionProperties(statistics: statistics, compSession: compSession)

            let frameSize = Int(Float(sourceWidth) * Float(sourceHeight) * 1.5)
            // Filehandling
            let fileURL = dir.appendingPathComponent(definition.input.filepath)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                log.info("Input media file: \(fileURL.path)")
            } else {
                log.error("Media file: \(fileURL.path) doe not exist")
                return "Error: no media file"
            }
            let outputURL = dir.appendingPathComponent("\(statistics.id!).mov")
            let outputPath = outputURL.path
            try? FileManager.default.removeItem(atPath: outputPath)

            // Nil for encoded data, only mov works
            let outputWriter = try AVAssetWriter(outputURL: outputURL, fileType: AVFileType.mov)
            outputWriterInput = AVAssetWriterInput(mediaType: AVMediaType.video, outputSettings: nil)
            outputWriter.add(outputWriterInput)

            outputWriter.startWriting()
            outputWriter.startSession(atSourceTime: CMTime.zero)

            var splitname = definition.input.filepath.components(separatedBy: "/")
            statistics.setSourceFile(filename: splitname[splitname.count - 1])
            splitname = outputPath.components(separatedBy: "/")
            statistics.setEncodedFile(filename: splitname[splitname.count - 1])
            var lastNow = timeStampNs()
            let realtime = definition.input.realtime
            if var stream: InputStream = InputStream(fileAtPath: fileURL.path) {
                stream.open()
                let pixelPool = VTCompressionSessionGetPixelBufferPool(compSession)
                statistics.start()

                while (!inputDone) {//} || !outputDone) {
                         if (inputFrameCounter % 100 == 0) {
                             log.info("""
                             \(definition.common.id) - BufferEncoder: frames: \(framesAdded) \
                        input frames: \(inputFrameCounter) current loop: \(currentLoop) current time: \(currentTimeSec)
                        """)
                             let now = timeStampNs()
                             log.info("time since last time \((Float(now)-Float(lastNow))/1000_000_000.0)")
                             lastNow = now
                         }
                    if doneReading(test: definition, stream: stream, frame: framesAdded, time: currentTimeSec, loop: false) {
                        inputDone = true
                    }
                    let size = queueInputBuffer(stream: stream, pixelPool: pixelPool!, frameSize: frameSize, realtime: realtime)
                    if size == -2 {
                        continue;
                    } else if (size <= 0 ) {
                        // restart loop
                        stream.close()
                        currentLoop += 1

                        if doneReading(test: definition, stream: stream, frame: framesAdded, time: currentTimeSec, loop: true) {
                            inputDone = true;
                        } else {
                            log.info(" *********** OPEN FILE AGAIN *******");
                            stream = InputStream(fileAtPath: fileURL.path)!
                            stream.open()
                            log.info("*** Loop ended start \(currentLoop) ***");
                        }
                    }
                }

                statistics.stop()
                //Flush
                let framePts = computePresentationTimeUsec(frameIndex: inputFrameCounter, frameTimeUsec: inputFrameDurationUsec, offset: Int64(pts))
                let lastTime = CMTime.init(value: Int64(framePts), timescale: CMTimeScale(scale))
                VTCompressionSessionCompleteFrames(compSession, untilPresentationTimeStamp: lastTime)

                outputWriterInput.markAsFinished()
                log.info("Wait for all pending frames")
                sleep(1)
                log.info("Call writer finish")
                outputWriter.finishWriting {
                    sleep(1)
                }

                while outputWriter.status == AVAssetWriter.Status.writing {
                    sleep(1)
                }

                stream.close()
                VTCompressionSessionInvalidate(compSession)
            }
        }
        log.info("Done, leaving encoder, encoded: \(statistics.encodedFrames.count)")
        return ""
    }

    func writeData(sampleBuffer: CMSampleBuffer, infoFlags: VTEncodeInfoFlags) -> Void {
        let tmp = UnsafeMutablePointer<UInt8>.allocate(capacity: sampleBuffer.totalSampleSize)
        var buffer: UnsafeMutablePointer<Int8>?
        let status = CMBlockBufferAccessDataBytes((sampleBuffer.dataBuffer)!, atOffset: 0, length: sampleBuffer.totalSampleSize, temporaryBlock: tmp, returnedPointerOut: &buffer )

        if status != noErr {
            log.error("Failed to get base address for blockbuffer")
            return
        }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let rawDic: UnsafeRawPointer = CFArrayGetValueAtIndex(attachments, 0)
            let dic: CFDictionary = Unmanaged.fromOpaque(rawDic).takeUnretainedValue()

            let keyFrame = !CFDictionaryContainsKey(dic, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque())
            //let dependOnOther = CFDictionaryContainsKey(dic, Unmanaged.passUnretained(kCMSampleAttachmentKey_DependsOnOthers).toOpaque())

            statistics.stopEncoding(pts: sampleBuffer.presentationTimeStamp.value, size: Int64(sampleBuffer.totalSampleSize), isKeyFrame: keyFrame)
            currentTimeSec = Float(sampleBuffer.presentationTimeStamp.value) / Float(scale)
            framesAdded += 1

            if outputWriterInput.isReadyForMoreMediaData {
                outputWriterInput.append(sampleBuffer)
            } else {
                log.error("Writer not ready for input")
            }
        }
        tmp.deallocate()
    }


    func sleepUntilNextFrame() {
        let now = timeStampNs() / 1000000
        if lastTimeMs <= 0 {
            lastTimeMs = now
            return;
        }
        // This sleep is controlled bu the input framerate
        var sleepTimeMs = frameDurationMs - Float(now - lastTimeMs)
        if sleepTimeMs < 0 {
            sleepTimeMs = 0
        }
        if sleepTimeMs > 0 {
            usleep(UInt32(sleepTimeMs * 1000))
        }
        lastTimeMs = timeStampNs()/1000000
    }



    func queueInputBuffer(stream: InputStream, pixelPool: CVPixelBufferPool, frameSize: Int, realtime: Bool) -> Int {
        var pixelBuffer : CVPixelBuffer? = nil
        if !stream.hasBytesAvailable {
            log.info("Nothing more to read")
            return -1;
        }
        var status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pixelPool, &pixelBuffer)
        if status != noErr {
            log.error("Pixel buffer create failed, status: \(status)")
            //break
        }

        status = CVPixelBufferLockBaseAddress(pixelBuffer!, CVPixelBufferLockFlags(rawValue: 0));
        let baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer!, 0)

        // READ
        var read = stream.read(baseAddress!, maxLength: frameSize)
        var timeInfo = CMSampleTimingInfo()
        let framePts = computePresentationTimeUsec(frameIndex: inputFrameCounter, frameTimeUsec: inputFrameDurationUsec, offset: Int64(pts))
        setRuntimeParameters(frame: Int64(inputFrameCounter));
        dropNext = dropFrame(frame: Int64(inputFrameCounter))
        updateDynamicFramerate(frame: Int64(inputFrameCounter));
        if !dropNext {
            dropNext = dropFromDynamicFramerate(frame: inputFrameCounter, keepInterval: keepInterval);
        }
        inputFrameCounter += 1
        if dropNext {
            dropNext = false
            skipped += 1
            read = -2 //TODO: enum
        } else if (read == frameSize) {
            timeInfo.presentationTimeStamp = CMTime.init(value: Int64(framePts), timescale: CMTimeScale(scale))
            timeInfo.duration =  CMTime.init(value: Int64(frameDurationUsec), timescale: CMTimeScale(scale))
            timeInfo.decodeTimeStamp = timeInfo.presentationTimeStamp
            var infoFlags = VTEncodeInfoFlags()

            if realtime {
                sleepUntilNextFrame()
            }
            statistics.startEncoding(pts: Int64(framePts), originalFrame: inputFrameCounter)
            status = VTCompressionSessionEncodeFrame(compSession,
                                                     imageBuffer: pixelBuffer!,
                                                     presentationTimeStamp:  timeInfo.presentationTimeStamp,
                                                     duration: timeInfo.duration,//CMTime.invalid,
                                                     frameProperties: nil,
                                                     sourceFrameRefcon: Unmanaged.passUnretained(self).toOpaque(),
                                                     infoFlagsOut: &infoFlags)
            lastPts = timeInfo.presentationTimeStamp
            if status != noErr {
                log.error("Encode frame failed, status: \(status)")
               // break
            }

        } else {
            log.info("Could not read all - only: \(read)")
            read = -1
        }
       status = CVPixelBufferUnlockBaseAddress(pixelBuffer!, CVPixelBufferLockFlags(rawValue: 0));
       return read
    }


    func updateDynamicFramerate(frame:Int64) {
        for rate in definition.runtime.dynamicFramerate {
            if rate.framenum == frame {
                keepInterval = inputFrameRate / rate.framerate;
                frameDurationUsec = calculateFrameTimingUsec(frameRate: rate.framerate);
                return;
            }
        }
    }



    func dropFrame(frame: Int64)-> Bool {
        for drop in definition.runtime.drop {
            if drop == frame {
                return true;
            }
        }

        return false;
    }

    func setRuntimeParameters(frame: Int64) {
        if !definition.hasRuntime {
            return
        }

        for setting in  definition.runtime.videoBitrate {
            if setting.framenum == frame {
                setBitrate(compSession: compSession, bps: magnitudeToInt(stringValue: setting.bitrate), cbr: false)
            }
        }

        for framenum in definition.runtime.requestSync {
            if framenum == frame {
                let status = VTSessionSetProperty(compSession, key: kVTEncodeFrameOptionKey_ForceKeyFrame, value: true as CFBoolean)
                if status != 0 {
                    log.error("failed force key frame, status: \(status)")
                }
            }
        }

        for setting in  definition.runtime.parameter {
            if setting.framenum == frame {
                switch setting.type {
                case DataValueType.floatType:
                    break
                case DataValueType.intType:
                    break
                case DataValueType.longType:
                    break
                case DataValueType.stringType:
                    break
                }
            }
        }
    }

    func fail(cause: String) {
        log.error("Encode failed: \(cause)")
        //inputDone = true
    }
}
