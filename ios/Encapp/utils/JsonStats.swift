//
//  JsonStats.swift
//  Encapp
//
//  Created by Johan Blome on 12/1/22.
//

import Foundation

struct JsonStats: Encodable {
    var id: String
    var description: String
    var test: JsonTest
    var environment: String
    var codec: String
    var meanbitrate: Int
    var date: String
    var encapp_version: String
    var proctime: Int64
    var framecount: Int
    var encodedfile: String
    var sourcefile: String
    //? var encoder_media_format: String
    var encoderprops: Array<JsonProperties>
    var frames: Array<JsonFrameInfo>
    var decoded_frames: Array<JsonFrameInfo>

}

struct JsonTest: Encodable {
    var input: JsonInput
    var common: JsonCommon
    var configure: JsonConfigure
    var runtime: Array<JsonRuntime>
}

struct JsonFrameInfo: Encodable {
    var frame: Int
    var original_frame: Int
    var iframe: Bool
    var size: Int64
    var pts: Int64
    var starttime: Int64
    var stoptime: Int64
    var proctime: Int64
}

struct JsonInput: Encodable {
    var filepath: String
    var resolution: String
    var pixFmt: String
    var framerate: Float
    var playoutFrames: Int
    var pursuit: Int
    var realtime: Bool
    var stoptimeSec: Float
    var show: Bool
}

struct JsonConfigure: Encodable {
    var parameter: Array<JsonParameter>
    var codec: String
    var encode: Bool
    var surface: Bool
    var mime: String
    var bitrate: String
    var bitrateMode: String
    var durationUs: Int64
    var resolution: String
    var colorFormat: UInt32
    var colorStandard: String
    var colorRange: String
    var colorTransfer: String
    var colorTransferRequest: String
    var framerate: Float
    var iFrameInterval: Int
    var intraRefreshPeriod: Int
    var latency: Int
    var repeatPreviousFrameAfter: Int64
    var tsSchema: String
    var quality: Int
    var complexity: Int
}

struct JsonParameter: Encodable {
    var frameNum: Int64
    //TODO: etc
}

struct JsonRuntime: Encodable {
    var frameNum: Int64
    var key: String
    var type: String
    var value: String
}


struct JsonCommon: Encodable {
    var id: String
    var description: String
    var operation: String
    var start: String
}

struct JsonProperties: Encodable {
    var key: String
    var value: String
}
