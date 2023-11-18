//
//  ContentView.swift
//  Encapp
//
//  Created by Johan Blome on 11/22/22.
//

import SwiftUI

struct ContentView: View {
    @State private var logText: String = "test"
    @State private var logTests: String = "test"

    

    var body: some View {
#if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = true
#endif
        let main = EncappMain()
        main.run()
        
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            DispatchQueue.main.async {
                let text = log.logText()
                if text.count > 1000 {
                    logText =  String(text.dropFirst(text.count - 1000)) as String
                } else {
                    logText = text
                }
                
            }
        }
        
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
            DispatchQueue.main.async {
                logTests = overview.testsLogText()

            }
        }
          
        return TabView {
                TextEditor(text: $logTests)
                    .disableAutocorrection(true)
                    .padding()
                    .border(Color.green)
                    .tabItem { Label("Tests", systemImage: "tray.and.arrow.down.fill")}
                TextEditor(text: $logText)
                    .disableAutocorrection(true)
                    .padding()
                    .border(Color.red)
                    .tabItem { Label("Log", systemImage: "tray.and.arrow.down.fill")}
                    
               }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        Text("")
    }
}


struct TextView: View {
    @Binding var text: String
    var body: some View {
        Text(text)
    }
}

