//
//  ListProperties.swift
//  Encapp
//
//  Created by Johan Blome on 11/22/22.
//

import Foundation
import VideoToolbox

struct JsonItem: Encodable {
    var key: String
    var value: String
}

struct JsonCodec: Encodable {
    var name: String
    var info: [JsonItem]
}

struct JsonCodecs: Encodable {
    var encoders: [JsonCodec]
    var decoders: [JsonCodec]
}

struct ListProps {
    func retrievePropsJson() -> String {
        let jsonEncoder = JSONEncoder()
        var list: CFArray? = nil
        let status = VTCopyVideoEncoderList(nil, &list)
        var encoders = [[String: String]]()
        var all_codecs = [String: [[String: String]]]()
        if status == 0 {
            let codecs = list as? [Any]
            for enc in codecs ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                var enc_dict = [String: String]()
                var name = encoder["EncoderID"]
                enc_dict["name"] = name?.description

                for item in encoder {
                    // DisplayName is the real name we will save for usage
                    if item.key.description == "EncoderID" {
                        name = item.value.description
                    }
                    enc_dict[item.key.description] = item.value.description
                }
                encoders.append(enc_dict)
            }
            all_codecs["encoders"] = encoders

        } else {
            print("No list \(status)")
        }

        jsonEncoder.outputFormatting = .prettyPrinted
        guard let json = try? jsonEncoder.encode(all_codecs) else {
            log.error("Failed to create json data")
            return ""

        }
        return String(data: json, encoding: .utf8)!
    }

    func retrieveProps() -> String {
        var output = ""
        var list: CFArray? = nil
        print("Create encoder list")
        output.append("Encoders\n")
        let status = VTCopyVideoEncoderList(nil, &list)

        if status == 0 {
            let encoders = list as? [Any]
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]

                for item in encoder {
                    output.append("\(item.key): \(item.value)\n")
                }
                output.append("---\n")
            }
        } else {
            print("No list \(status)")
        }

        //CFRelease(encoders)
        return output
    }

    func getCodecIdFromType(encoderType: UInt32) -> String {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if status == 0 {
            let encoders = list as? [Any]
            // First look for encoder name
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                let encType = encoder["CodecType"] as! UInt32
                if encoderType == encType {
                    return encoder["EncoderID"] as! String
                }
            }
        }
        return ""
    }

    func getCodecNameFromType(encoderType: UInt32) -> String {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if status == 0 {
            let encoders = list as? [Any]
            // First look for encoder name
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                let encType = encoder["CodecType"] as! UInt32
                if encoderType == encType {
                    return encoder["EncoderName"] as! String
                }
            }
        }
        return ""
    }

    func lookupCodectType(name: String) -> UInt32 {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if status == 0 {
            let encoders = list as? [Any]
            // First look for exact 'encoder id' match
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                let encoderId = encoder["EncoderID"] as! String
                if encoderId.lowercased() == (name.lowercased()) {
                    log.info("Matched encoder id name: \(encoderId)")
                    return encoder["CodecType"] as! UInt32
                }

            }
            // Then look for partial matching encoder name
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                let encoderName = encoder["EncoderName"] as! String
                log.info("\(enc)")
                if encoderName.lowercased().contains(name.lowercased()) {
                    log.info("Matched encoder name: \(encoderName)")
                    return encoder["CodecType"] as! UInt32
                }

            }
            // Then look for 'codec name'
            for enc in encoders ?? [] {
                let encoder = enc as! [AnyHashable: AnyHashable]
                let codecName = encoder["CodecName"] as! String
                if codecName.lowercased().contains(name.lowercased()) {
                    log.info("Matched codec name: \(codecName)")
                    return encoder["CodecType"] as! UInt32
                }

            }
        } else {
            print("No list \(status)")
        }

        return 0
    }
}
