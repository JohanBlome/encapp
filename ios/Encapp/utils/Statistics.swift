//
//  Statistics.swift
//  Encapp
//
//  Created by Johan Blome on 12/1/22.
//

import Foundation
import SwiftProtobuf

class Statistics {
    var appVersion = ""
    var test: Test!
    var date: Date?
    var startTime = -1 as Int64?
    var stopTime = -1 as Int64?
    var encoderName = ""
    var id: String?
    var description: String?
    var encodedFile: String = ""
    var sourceFile: String = ""
    var encodingFrames: Array<FrameInfo>
    var encodedFrames: Array<FrameInfo>
    var decodedFrames: Array<FrameInfo>
    var frameLock = NSLock()
    var props: Array<JsonProperties>

    init(description: String, test: Test) {
        self.description = description
        self.test = test
        self.encodingFrames = Array<FrameInfo>()
        self.encodedFrames = Array<FrameInfo>()
        self.decodedFrames = Array<FrameInfo>()
        self.props = Array<JsonProperties>()
        date = Date();
        id = "encapp_\(UUID().uuidString)"
    }

    func start() {
        startTime = timeStampNs()
    }

    func stop() {
        stopTime = timeStampNs()
    }

    func startEncoding(pts: Int64, originalFrame: Int) {
        let frameInfo = FrameInfo(pts: pts, originalFrame: originalFrame)
        frameInfo.start()
        frameLock.lock()
        encodingFrames.append(frameInfo)
        frameLock.unlock()
    }


    func stopEncoding(pts: Int64,size: Int64, isKeyFrame: Bool) {
        guard let frameInfo = getClosestEncodingMatch(pts: pts, array: encodingFrames) else {
            log.error("Error: could not find a matching frame")
            return
        }
        frameInfo.stop()
        frameInfo.setSize(size: size)
        frameInfo.setKeyFrame(isKeyFrame: isKeyFrame)
        frameLock.lock()
        encodedFrames.append(frameInfo)
        frameLock.unlock()
        if let index = encodingFrames.firstIndex(of:frameInfo) {
            frameLock.lock()
            encodingFrames.remove(at:index)
            frameLock.unlock()
        }

    }

    func startDecoding(pts: Int64) {
        let frameInfo = FrameInfo(pts: pts)
        frameInfo.start()
        frameLock.lock()
        decodedFrames.append(frameInfo)
        frameLock.unlock()
    }




    func stopDeccoding(pts: Int64) {
        guard let frameInfo = getClosestEncodingMatch(pts: pts, array: decodedFrames) else {
            log.error("Error: could not find a matching frame")
            return
        }
        frameInfo.stop()
    }


    func getClosestEncodingMatch(pts: Int64, array: Array<FrameInfo>) -> FrameInfo? {
        var minDist = Int64.max
        var frameInfo: FrameInfo?

        frameLock.lock()
        for info in array {
            let dist = abs(pts - info.pts)
            if dist < minDist {
                minDist = dist
                frameInfo = info
            }
        }
        frameLock.unlock()
        return frameInfo
    }

    func getAverageBitrate(frames: Array<FrameInfo>)->Int {
        return 0
    }

    func getProcessingTime() -> Int64 {
        return (stopTime! - startTime!);
    }

    func setEncoderName(encoderName: String) {
        self.encoderName = encoderName
    }

    func setSourceFile(filename: String) {
        self.sourceFile = filename
    }

    func setEncodedFile(filename: String) {
        self.encodedFile = filename
    }

    func getJson() -> String {
        let jsonEncoder = JSONEncoder()

        //Convert frame info
        var eFrames = Array<JsonFrameInfo>()
        var frameCount = 0
        frameLock.lock()
        if encodedFrames.count > 0 {
            for info in encodedFrames {
                let json = JsonFrameInfo(frame: frameCount,
                                         original_frame: info.getOriginalFrame(),
                                         iframe: info.isKeyFrame(),
                                         size: info.getSize(),
                                         pts: info.getPts(),
                                         starttime: info.getStartTime(),
                                         stoptime: info.getStopTime(),
                                         proctime: info.getProcessingTime()
                                        )
                frameCount += 1
                eFrames.append(json)
            }
        }
        frameLock.unlock()
        frameCount = 0
        var dFrames = Array<JsonFrameInfo>()
        frameLock.lock()
        if decodedFrames.count > 0 {
            for info in decodedFrames {
                let json = JsonFrameInfo(frame: frameCount,
                                         original_frame: frameCount,
                                         iframe: info.isKeyFrame(),
                                         size: info.getSize(),
                                         pts: info.getPts(),
                                         starttime: info.getStartTime(),
                                         stoptime: info.getStopTime(),
                                         proctime: info.getProcessingTime()
                                        )
                frameCount += 1
                dFrames.append(json)
            }
        }
        frameLock.unlock()
        let input = test.input
        let jinput = JsonInput(filepath: input.filepath, resolution: input.resolution, pixFmt: pixFmtToString(format: input.pixFmt), framerate: input.framerate, playoutFrames: Int(input.playoutFrames), pursuit: Int(input.pursuit), realtime: input.realtime, stoptimeSec: input.stoptimeSec, show: input.show)

        let conf = test.configure
        let runtime = test.runtime
        var jruntime = Array<JsonRuntime>()
        for item in runtime.parameter {
            //TODO: fix th value type
            let jrt = JsonRuntime(frameNum: item.framenum, key: item.key, type: String(item.type.rawValue), value: item.value)
            jruntime.append(jrt)
        }

        let jparams = Array<JsonParameter>()

        let jconfigure = JsonConfigure(parameter: jparams, codec: encoderName, encode: conf.encode, surface: conf.surface, mime: conf.mime, bitrate: conf.bitrate, bitrateMode: String(conf.bitrateMode.rawValue), durationUs: Int64(conf.durationUs), resolution: conf.resolution, colorFormat: conf.colorFormat.magnitude, colorStandard: conf.colorStandard.rawValue.formatted(), colorRange: conf.colorRange.rawValue.formatted(), colorTransfer: conf.colorTransfer.rawValue.formatted(), colorTransferRequest: conf.colorTransferRequest, framerate: conf.framerate, iFrameInterval: Int(conf.iFrameInterval), intraRefreshPeriod: Int(conf.intraRefreshPeriod), latency: Int(conf.latency), repeatPreviousFrameAfter: conf.repeatPreviousFrameAfter, tsSchema: conf.tsSchema, quality: Int(conf.quality), complexity: Int(conf.complexity))

        let common = test.common
        let jcommon = JsonCommon(id: common.id, description: common.description_p, operation: common.operation, start: common.start)

        let jtest = JsonTest(input: jinput, common: jcommon, configure: jconfigure, runtime: jruntime)
        let environment = "ios"

        let stats = JsonStats(id: test.common.id,
                              description: (test?.common.debugDescription)!,
                              test: jtest,
                              environment: environment,
                              codec: encoderName,
                              meanbitrate: getAverageBitrate(frames: encodedFrames),
                              date:  DateFormatter().string(from: date!),
                              encapp_version: appVersion,
                              proctime: getProcessingTime(),
                              framecount: encodedFrames.count,
                              encodedfile: encodedFile,
                              sourcefile: sourceFile,
                              encoderprops: props,
                              //? encoder_media_format: "",

                              frames: eFrames,
                              decoded_frames: dFrames)

        jsonEncoder.outputFormatting = .prettyPrinted
        guard let json = try? jsonEncoder.encode(stats) else {
            log.error("Failed to create json stats")
            return ""

        }

        return String(data: json, encoding: .utf8)!
    }


    func addProp(name: String, val: String) {
        log.debug("Add prop: \(name) with value \(val)")
        props.append(JsonProperties(key: name, value: val))
    }
}

