//
//  EncappMain.swift
//  Encapp
//
//  Created by Johan Blome on 12/5/22.
//

import Foundation
#if os(iOS)
import UIKit
#endif
import SwiftUI

class EncappMain {
    @State var logText: String = "Encapp"
    let runningLockName = "running.lock"


    func run() {
        log.info("Starting encapp: \(CommandLine.arguments)")
        if CommandLine.arguments.count < 2 {
            log.error("No commands")
            log.info("No command given")
            exit(1)
        }

        // We are assuming things about the arguments depending on type
        let command = CommandLine.arguments[1] as String
        overview.updateTestsLog(text: "Running command: \'\(command)\'")
        if command == "list_codecs" {
            log.info("Retrieve codecs")
            let props = ListProps()
            let output = props.retrieveProps()
            log.info(output)
            //TODO: write to file
            let io = FileIO()
            log.info("Write codecx.txt")
            io.writeData(filename: "codecs.txt", data: output)
            completion()
        } else if command == "reset" {
            let io = FileIO()
            io.deleteEncappoutputFiles()
            completion()
        } else if command.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "test" {
            log.info("Running a test suite.")
            let io = FileIO()
            io.writeData(filename: runningLockName, data: "Running")
            if CommandLine.arguments.count < 3 {
                log.error("Command 'test' without test name")
                completion()
            }

            let testToRun = (CommandLine.arguments[2] as String).trimmingCharacters(in: .whitespacesAndNewlines)
            if !io.fileExist(filename: testToRun) {
                log.error("Non existing test file: \(testToRun)")
                completion()
            }
            log.info("Start testing: '\(testToRun)'")
            overview.updateTestsLog(text: "Starting test: '\(testToRun)'")
            let runner = TestRunner(filename: testToRun, completion: completion)
            runner.start()
        } else if command == "standby" {
           // This is only to keep the screen on while doing slow io
        }

    }

    func completion() {
        let io = FileIO()
        log.info("EncappMain: complete, close down")
        log.release()
        overview.updateTestsLog(text: "Done with all tests")
        sleep(1)
        io.deleteFile(filename: runningLockName)
        sleep(1)
        exit(0)
    }

}
