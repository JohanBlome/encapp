//
//  LocalLogger.swift
//  Encapp
//
//  Created by Johan Blome on 12/5/22.
//

import Foundation
let log = LocalLogger()
let overview = OverviewLogger()

struct LocalLogger {
    private var logWriter: LogWriter!
    
    init() {
        logWriter = LogWriter(filename: "encapp.log")
        logWriter.start()
    }
 
    func info(_ message:String) {
        logWriter.addEntry(tag: "info", message: message)
    }
    
    func debug(_ message:String) {
        logWriter.addEntry(tag: "debug", message: message)
    }
    
    func warning(_ message:String) {
        logWriter.addEntry(tag: "warning", message: message)
    }
    
    func error(_ message:String) {
        logWriter.addEntry(tag: "error", message: message)
    }
    
    func release() {
        logWriter.release()
    }
    
    func logText() -> String {
        return logWriter.logText
    }

}




class LogWriter: Thread {
    var filename: String!
    var loglist = Array<String>()
    var listLock = NSLock()
    var semaphore = DispatchSemaphore(value: 0)
    var done = false
    var logText: String = ""
  
    init(filename: String) {
        self.filename = filename
        loglist.append("Encapp log\n")
    }
    
    func addEntry(tag:String, message:String) {
        listLock.lock()
        loglist.append("\(tag) - \(message)")
        listLock.unlock()
        semaphore.signal()
    }
    
    func release() {
        done = true
        semaphore.signal()
    }
    
    override func main() { // Thread's starting point
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            print("Failed to get the doument folder")
            return
        }
        
        let outputURL = dir.appendingPathComponent(filename)
        try? FileManager.default.removeItem(atPath: outputURL.path)
        FileManager.default.createFile(atPath: outputURL.path, contents: nil)
        if let filehandle = try? FileHandle(forUpdating: outputURL) {
                while !done {
                    while(loglist.count > 0) {
                        listLock.lock()
                        let item = loglist.removeFirst()
                        listLock.unlock()
                        if item.count > 0 {
                            let data = "\(timestampDateTime()) \(item as String)\n"
                            filehandle.write(data.data(using: .utf8)!)
                            print("\(data)")
                            logText = "\(logText)\n\(data)"
                            do {
                                try filehandle.synchronize()
                            } catch {
                                print("Synch failed")
                            }
                        }
                    }
                    
                    semaphore.wait()
                }

            filehandle.closeFile()
        } else {
            print("Failed to open filehandle \(outputURL)")
        }


    }
    
  
}


class OverviewLogger {
    private var logText: String = ""
    func testsLogText() -> String {
        return logText
    }
    
    func updateTestsLog(text: String) {
        logText = "\(logText)\n\(text)"
    }

}
