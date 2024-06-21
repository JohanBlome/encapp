//
//  FileHandling.swift
//  Encapp
//
//  Created by Johan Blome on 11/22/22.
//

import Foundation

struct FileIO {
    func readData(filename: String) -> String {
        var text = ""

        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {

            let fileURL = dir.appendingPathComponent(filename)
            //reading
            do {
                text.append(try String(contentsOf: fileURL, encoding: .utf8))
            }
            catch {/* error handling here */}
        }
        return text
    }

    func writeData(filename: String, data: String) {
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            log.error("Failed to get the doument folder")
            return
        }

        let outputURL = dir.appendingPathComponent(filename)
        try? FileManager.default.removeItem(atPath: outputURL.path)
        FileManager.default.createFile(atPath: outputURL.path, contents: nil)
        if let filehandle = try? FileHandle(forUpdating: outputURL) {
            log.info("Write stats for \(filename)")
            filehandle.write(data.data(using: .utf8)!)
            filehandle.closeFile()
            do {
                try filehandle.close()

            } catch {
                log.info("filehandle close caused an error")
            }
        } else {
            log.error("Failed to open filehandle \(outputURL)")
        }
    }

    func writeArrayData(filename: String, data: [String]) {
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {

            let fileURL = dir.appendingPathComponent(filename, isDirectory: false)
            try? FileManager.default.removeItem(atPath: fileURL.path)
            //writing
            do {
                for line in data {
                    try line.write(to: fileURL, atomically: false, encoding: .utf8)
                }
            }
            catch {/* error handling here */}

        }
    }

    func readTestDefinition(inputfile: String)->TestSuite{
        print("Read file \(inputfile)")

        // Read testfile
        var tests = TestSuite()
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let fileURL = dir.appendingPathComponent(inputfile)
            log.info("Look for \(fileURL)")
            var text = ""
            do {
                text = try String(contentsOf: fileURL, encoding: .utf8)
                tests = try TestSuite.init(textFormatString: text)
            }
            catch {/* error handling here */
                log.error("Failed to read data: \(text)")
            }

        }
        return tests
    }

    func deleteFile(filename: String) {
        let fileManager = FileManager.default
        if let dir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            let fileURL = dir.appendingPathComponent(filename)
            do {
                let directoryContents = try fileManager.contentsOfDirectory(
                    at: dir,
                    includingPropertiesForKeys: nil
                )
                let files = directoryContents.filter{$0.absoluteString.contains(filename)}
                if files.count > 0 {
                    // Delete file
                    try fileManager.removeItem(at: fileURL)

                } else {
                    print("File does not exist")
                }

            } catch {
                log.error("Failed to remove: \(fileURL)")
            }
        }
    }


    func deleteEncappoutputFiles() {
        print("file exists? \(fileExist(filename: "running.lock"))")
        deleteFile(filename: "running.lock")
        let fileManager = FileManager.default
        if let dir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            do {
                let directoryContents = try fileManager.contentsOfDirectory(
                    at: dir,
                    includingPropertiesForKeys: nil
                )
                // Match filenames
                let files = directoryContents.filter{$0.absoluteString.contains("encapp_")}
                var filt = files.filter{$0.absoluteString.contains(".mov")}
                for file in filt {
                    do {
                        try fileManager.removeItem(at: file)
                    } catch {
                        log.error("Failed to remove, \(error): \(file)")
                    }
                }

                filt = files.filter{$0.absoluteString.contains(".json")}
                for file in filt {
                    do {
                        try fileManager.removeItem(at: file)
                    } catch {
                        log.error("Failed to remove, \(error): \(file)")
                    }
                }
            } catch {
                print("Problem with cleaning output files, \(error)")
            }
        }
    }

    func fileExist(filename: String) -> Bool {
        let fileManager = FileManager.default
        if let dir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            do {
                let directoryContents = try fileManager.contentsOfDirectory(
                    at: dir,
                    includingPropertiesForKeys: nil
                )
                let files = directoryContents.filter{$0.absoluteString.contains(filename)}
                return files.count > 0
            } catch {
                // Not much
            }
        }
        return false
    }
}
