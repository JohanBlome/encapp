//
//  ListProperties.swift
//  Encapp
//
//  Created by Johan Blome on 11/22/22.
//

import Foundation
import VideoToolbox

struct ListProps {
    func retrieveProps() -> String {
        var output = ""
        var list: CFArray? = nil
        print("Create encoder list")
        output.append("Encoders\n")
        let status = VTCopyVideoEncoderList(nil, &list)

        if (status == 0) {
            let encoders = list as? Array<Any>
            for enc in encoders ?? []{
                let encoder = enc    as! Dictionary<AnyHashable, AnyHashable>

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

    func getCodecIdFromType(encoderType: UInt32)->String {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if (status == 0) {
            let encoders = list as? Array<Any>
            // First look for encoder name
            for enc in encoders ?? []{
                let encoder = enc as! Dictionary<AnyHashable, AnyHashable>
                let encType = encoder["CodecType"] as! UInt32
                if encoderType ==  encType {
                    return encoder["EncoderID"] as! String
                }
            }
        }
        return ""
    }

    func getCodecNameFromType(encoderType: UInt32)->String {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if (status == 0) {
            let encoders = list as? Array<Any>
            // First look for encoder name
            for enc in encoders ?? []{
                let encoder = enc as! Dictionary<AnyHashable, AnyHashable>
                let encType = encoder["CodecType"] as! UInt32
                if encoderType ==  encType {
                    return encoder["EncoderName"] as! String
                }
            }
        }
        return ""
    }

    func lookupCodectType(name: String)->UInt32 {
        var list: CFArray!
        let status = VTCopyVideoEncoderList(nil, &list)

        if (status == 0) {
            let encoders = list as? Array<Any>
            // First look for exact 'encoder id' match
            for enc in encoders ?? []{
                let encoder = enc as! Dictionary<AnyHashable, AnyHashable>
                let encoderId = encoder["EncoderID"] as! String
                if encoderId.lowercased() == (name.lowercased()) {
                    log.info("Matched encoder id name: \(encoderId)")
                    return encoder["CodecType"] as! UInt32
                }

            }
            // Then look for partial matching encoder name
            for enc in encoders ?? []{
                let encoder = enc as! Dictionary<AnyHashable, AnyHashable>
                let encoderName = encoder["EncoderName"] as! String
                log.info("\(enc)")
                if encoderName.lowercased().contains(name.lowercased()) {
                    log.info("Matched encoder name: \(encoderName)")
                    return encoder["CodecType"] as! UInt32
                }

            }
            // Then look for 'codec name'
            for enc in encoders ?? []{
                let encoder = enc as! Dictionary<AnyHashable, AnyHashable>
                let codecName = encoder["CodecName"] as! String
                if codecName.lowercased().contains(name.lowercased()) {
                    log.info("Matched codec name: \(codecName)")
                    return encoder["CodecType"] as! UInt32
                }

            }
        } else {
            print("No list \(status)")
        }

        return 0;
    }
}
