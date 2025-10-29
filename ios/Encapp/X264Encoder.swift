//
//  Encoder.swift
//  Encapp
//
//  Created by Johan Blome on 11/23/22.
//

import Foundation
import VideoToolbox
import AVFoundation

typealias x264_t_ptr = OpaquePointer

class X264Encoder: Encoder{
    var x264Encoder: x264_t_ptr?
    var inputBuffer: UnsafeMutableRawPointer?
    var x264InputFrame: UnsafeMutablePointer<x264_picture_t>?
    var x264OutputFrame: UnsafeMutablePointer<x264_picture_t>?
    var yPlaneSize: Int!
    var uvPlaneSize: Int!
    var formatDescription: CMFormatDescription!
    
    override init(test: Test){
        super.init(test: test)
    }
    
    func parseConfiguration(test: Test, params: inout x264_param_t) -> Int {
        var preset = "fast"
        var tune: String? =  nil

        for param in test.configure.parameter {
            if param.key == "preset" {
                preset = param.value
                log.debug("Set preset: \(preset)")
            } else if param.key == "tune" {
                if param.value != "none" {
                    tune = param.value
                    log.debug("Set tune: \(tune)")
                }
            }
        }
        
        
        let result = x264_param_default_preset(&params, preset, tune)
        if result < 0 {
            log.error("Failed to set preset and tune")
        } else {
            log.debug("Preset and tune set successfully")
        }
        
        
        // Logging, skip unless needed.
        //params.pointee.i_csp = X264_CSP_I420
        //params.pointee.p_log_private = nil
        //params.pointee.i_log_level = Int32(X264_LOG_DEBUG) // Or another log level
        //x264_set_log_callback(params)
        
        return 0
    }
    
    
    override func Encode() throws  -> String {
        log.info("Starting x264 encoder")
        statistics = Statistics(description: "raw encoder", test: definition)
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
            self.inputBuffer = UnsafeMutableRawPointer.allocate(byteCount: width * height * 3/2, alignment: 16) // Really 1.5, no odd ones
            self.yPlaneSize = width * height
            self.uvPlaneSize = width * height / 4
            statistics.setEncoderName(encoderName: "x264")

            
            log.debug(String(format: "Create x264 encoder with %dx%d", width, height))
            var params = UnsafeMutablePointer<x264_param_t>.allocate(capacity: 1)
            //x264_param_default(params)
            if parseConfiguration(test: definition, params: &params.pointee) < 0 {
                log.debug("Failed to set preset ans tune")
            }

            params.pointee.i_width = Int32(width)
            params.pointee.i_height = Int32(height)
            var frameRate: Float = 30.0
            if definition.configure.hasFramerate {
                frameRate = definition.configure.framerate
            } else if definition.input.hasFramerate {
                frameRate = definition.input.framerate
            }
            params.pointee.i_fps_num = UInt32(frameRate)
            params.pointee.i_fps_den = 1
            
            // TODO: Bitrate mode is a mess, to bps
            params.pointee.rc.i_bitrate = Int32(magnitudeToInt(stringValue: definition.configure.bitrate))
            
            // i frame interval is in seconds on Android and in frames here, translate
            params.pointee.i_frame_reference = Int32(frameRate)
            
            //TODO: default?
            params.pointee.i_threads = 0
            
            params.pointee.i_timebase_den = 1
            params.pointee.i_timebase_num = 1000000

            self.x264Encoder = x264_encoder_open_157(&params.pointee)
            self.x264InputFrame = UnsafeMutablePointer<x264_picture_t>.allocate(capacity: 1)
            self.x264OutputFrame = UnsafeMutablePointer<x264_picture_t>.allocate(capacity: 1)
            x264_picture_init(x264InputFrame)
            x264_picture_alloc(x264InputFrame, X264_CSP_I420, Int32(sourceWidth), Int32(sourceHeight))
            x264_picture_init(x264OutputFrame)


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
                    
                    // PUSH
                    let size = queueInputBuffer(stream: stream, frameSize: frameSize, realtime: realtime)
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
            }
            x264_picture_clean(x264InputFrame)
            x264InputFrame!.deallocate()
            x264OutputFrame!.deallocate()
        }
        log.info("Done, leaving encoder, encoded: \(statistics.encodedFrames.count)")
        return ""
    }
    
    func convertAnnexBToAVCC(nals: [x264_nal_t]) -> Data {
        var avccData = Data()
        for nal in nals {
            var length = UInt32(nal.i_payload).bigEndian
            avccData.append(Data(bytes: &length, count: 4))
            avccData.append(Data(bytes: nal.p_payload, count: Int(nal.i_payload)))
        }
        return avccData
    }
    
    func stripStartCode(_ payload: UnsafePointer<UInt8>, length: Int) -> [UInt8] {
        var offset = 0
        // Check for 4-byte start code
        if length > 4 && payload[0] == 0 && payload[1] == 0 && payload[2] == 0 && payload[3] == 1 {
            offset = 4
        } else if length > 3 && payload[0] == 0 && payload[1] == 0 && payload[2] == 1 {
            offset = 3
        }
        return Array(UnsafeBufferPointer(start: payload + offset, count: length - offset))
    }
    
    func createCMSampleBuffer(from avccData: Data, pts: CMTime, dts: CMTime, formatDesc: CMFormatDescription) -> CMSampleBuffer? {
        var blockBuffer: CMBlockBuffer?
        let status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: UnsafeMutableRawPointer(mutating: (avccData as NSData).bytes),
            blockLength: avccData.count,
            blockAllocator: kCFAllocatorNull,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avccData.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == kCMBlockBufferNoErr, let blockBuffer = blockBuffer else { return nil }
        var sampleBuffer: CMSampleBuffer?
        let sampleSizes = [avccData.count]
        let err = CMSampleBufferCreate(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: formatDesc,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: [CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: dts)],
            sampleSizeEntryCount: 1,
            sampleSizeArray: sampleSizes,
            sampleBufferOut: &sampleBuffer
        )
        return (err == noErr) ? sampleBuffer : nil
    }
    
    func writeData(sampleBuffer: CMSampleBuffer) -> Void {
        let size = sampleBuffer.totalSampleSize
        var buffer = [UInt8](repeating: 0, count: size)
        var bufferPtr: UnsafeMutablePointer<Int8>?
        let status = buffer.withUnsafeMutableBytes { tmp in
            CMBlockBufferAccessDataBytes(
                (sampleBuffer.dataBuffer)!,
                atOffset: 0,
                length: size,
                temporaryBlock: tmp.baseAddress!,
                returnedPointerOut: &bufferPtr
            )
        }
        guard status == noErr else {
            log.error("Failed to get base address for blockbuffer")
            return
        }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let rawDic: UnsafeRawPointer = CFArrayGetValueAtIndex(attachments, 0)
            let dic: CFDictionary = Unmanaged.fromOpaque(rawDic).takeUnretainedValue()

            //let keyFrame = !CFDictionaryContainsKey(dic, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque())
            currentTimeSec = Float(sampleBuffer.presentationTimeStamp.value) / Float(scale)
            framesAdded += 1

            if outputWriterInput.isReadyForMoreMediaData {
                outputWriterInput.append(sampleBuffer)
            } else {
                log.error("Writer not ready for input")
            }
        }
    }




    func queueInputBuffer(stream: InputStream, frameSize: Int, realtime: Bool) -> Int {
        if !stream.hasBytesAvailable {
            log.info("Nothing more to read")
            return -1;
        }
 
        var read = stream.read(inputBuffer!, maxLength: frameSize)
        if read == 0 {
            log.info("End of stream")
            return -1;
        }
       

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

            if realtime {
                sleepUntilNextFrame()
            }
            
            guard let dataloc = inputBuffer else {
                log.error("No data from stream")
                return -1;
            }

            // Copy Y plane
            memcpy(x264InputFrame!.pointee.img.plane.0, dataloc, yPlaneSize)

            // Copy U plane
            let uPointer = dataloc.advanced(by: yPlaneSize)
            memcpy(x264InputFrame!.pointee.img.plane.1, uPointer, uvPlaneSize)

            // Copy V plane
            let vPointer = dataloc.advanced(by: yPlaneSize + uvPlaneSize)
            memcpy(x264InputFrame!.pointee.img.plane.2, vPointer, uvPlaneSize)
            
            x264InputFrame!.pointee.i_pts = Int64(framePts)
            var nalPtr: UnsafeMutablePointer<x264_nal_t>? = nil
            var piNal: Int32 = 0
            // Write nals to file or process
            statistics.startEncoding(pts: Int64(framePts), originalFrame: inputFrameCounter)
            let bytes = x264_encoder_encode(self.x264Encoder, &nalPtr, &piNal, x264InputFrame!, x264OutputFrame!)
                
            
            var keyFrame = false
            if piNal > 0 {
                let nals = Array(UnsafeBufferPointer(start: nalPtr, count: Int(piNal)))
                let avcc = convertAnnexBToAVCC(nals: nals)
                let pts = CMTime(value: x264OutputFrame!.pointee.i_pts, timescale: CMTimeScale(scale))
                let dts = CMTime(value: x264OutputFrame!.pointee.i_dts, timescale: CMTimeScale(scale))
                
                if formatDescription == nil {
                    // Looks for sps and ppa and create format
                    let nals = Array(UnsafeBufferPointer(start: nalPtr, count: Int(piNal)))
                    var sps: [UInt8] = []
                    var pps: [UInt8] = []
                    var status: OSStatus = -1
                    for nal in nals {
                        if nal.i_type == 7 { // SPS
                            sps = stripStartCode(nal.p_payload, length: Int(nal.i_payload))
                        } else if nal.i_type == 8 { // PPS
                            pps = stripStartCode(nal.p_payload, length: Int(nal.i_payload))
                        }
                    }
                    
                    // Only proceed if both SPS and PPS are found
                    guard !sps.isEmpty, !pps.isEmpty else {
                        print("SPS or PPS not found")
                        return -1
                    }
                    sps.withUnsafeBytes { spsBuffer in
                        pps.withUnsafeBytes { ppsBuffer in
                            let parameterSetPointers: [UnsafePointer<UInt8>] = [
                                spsBuffer.baseAddress!.assumingMemoryBound(to: UInt8.self),
                                ppsBuffer.baseAddress!.assumingMemoryBound(to: UInt8.self)
                            ]
                            let parameterSetSizes: [Int] = [sps.count, pps.count]
                            status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                                allocator: kCFAllocatorDefault,
                                parameterSetCount: 2,
                                parameterSetPointers: parameterSetPointers,
                                parameterSetSizes: parameterSetSizes,
                                nalUnitHeaderLength: 4,
                                formatDescriptionOut: &formatDescription
                            )
                        }
                    }
                    
                    if status != noErr {
                        log.error("Failed to extract sps/pps")
                        return -1
                    }
                }
                let buffer = createCMSampleBuffer(from: avcc, pts: pts, dts: dts, formatDesc: formatDescription)
                keyFrame = (((x264OutputFrame!.pointee.i_type == X264_TYPE_AUTO || x264OutputFrame!.pointee.i_type == X264_TYPE_IDR) ? 1 : 0) != 0)
                statistics.stopEncoding(pts: framePts, size: Int64(bytes), isKeyFrame: keyFrame)
                writeData(sampleBuffer: buffer!)
                
            } else {
                log.debug("No nals")
            }

            lastPts = timeInfo.presentationTimeStamp

        } else {
            log.info("Could not read all - only: \(read)")
            read = -1
        }

        return read
    }



    override func setRuntimeParameters(frame: Int64) {
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
    
}
