//
//  TestRunner.swift
//  Encapp
//
//  Created by Johan Blome on 12/1/22.
//

import Foundation

class TestRunner: Thread {
    var filename: String
    var overrideInput: String? = nil
    var testsRunning = Array<Thread>()
    var isRunning = true
    var completion: ()->()?
    
    init(filename: String, input: String, completion: @escaping ()->()) {
        self.filename = filename
        self.completion = completion
        self.overrideInput = input
    }

    override func main() { // Thread's starting point
        runTest()
    }


    func runTest() {
        log.info("Starting threaded test")
        let io = FileIO()
        let testsuite = io.readTestDefinition(inputfile: filename)
        
        if testsuite.test.count == 0 {
            log.error("No test, probably faulty path or definition")
            return
        }
        
        var counter = 1
        for test in testsuite.test {
            let descr = "** Running \(counter)/\(testsuite.test.count), test: \(test.common.id)"
            log.info(descr)
            overview.updateTestsLog(text: descr)
            var test_ = test
            if overrideInput!.count > 0 {
                test_.input.filepath = overrideInput!
            }
            counter += 1
            //TODO: add start sync
            if test_.hasParallel {
                for parallel in test_.parallel.test {
                    let task = RunSingleTest(test: parallel, completion: completion)

                    testsRunning.append(task)
                    log.info("** start para")
                    task.start()
                }
            }
            let task = RunSingleTest(test: test_, completion: completion)
            log.info("** start blocking")
            testsRunning.append(task)
            task.start()
            log.info("** wait for completion")
            task.waitForCompletion()
            log.info("** Completed ")

        }
        self.completion()
    }

    func testDone(statistics: Statistics) {
        let data = statistics.getJson()
        let io = FileIO()
        io.writeData(filename:  "\(statistics.id!).json", data: data)
        log.info("Stats written for \(statistics.test.common.id)")
    }

    func completion(singleTest: RunSingleTest) {
        log.info("Remove finished task \(singleTest.description), left: \(testsRunning.count)")
        testDone(statistics: singleTest.statistics)
        testsRunning.remove(at: testsRunning.firstIndex(of: singleTest)!)
    }

    class RunSingleTest: Thread {
        var test: Test
        var done = false
        var completion: (RunSingleTest)->()
        var statistics: Statistics!
        var sem: DispatchSemaphore

        init(test: Test, completion:  @escaping (RunSingleTest)->()) {
            self.test = test
            self.completion = completion
            self.sem = DispatchSemaphore(value: 0)
        }

        override func main() { // Thread's starting point
            do {
                //Decide what to do based on the source
                if isKnownEncodingExtension(videopath: test.input.filepath) {
                    print("Decode video")
                    let decoder = Decoder(test: test)
                    let result = try decoder.Decode()
                    statistics = decoder.statistics
                } else if test.input.filepath.fileExtension() == "camera" {
                    print("Camera source")
                } else {
                    //TODO: fix later, for now simple things
                    log.info("Start encoding test: \(test.common.id)")
                    // Check the encoder if x264 choose the x264 encoder (duh)
                    let encoder: Encoder
                    if test.configure.codec.contains("x264") {
                        #if X264
                        encoder = X264Encoder(test: test)
                        #else
                        log.error("X264 build is not included in this build")
                        throw EncappErrors.x264NotAvailable
                        #endif
                    } else {
                        encoder = Encoder(test: test)
                    }
                    let result = try encoder.Encode()
                    statistics = encoder.statistics
                    log.info("\(result)")
                    log.info("Done testing: ")
                }
                done = true
                self.completion(self)
            }  catch {
                log.error("Error running single test")
            }

            sem.signal()
        }

        func waitForCompletion() {
            if !done {
                sem.wait()
            }
        }
    }
}
