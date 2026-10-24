import CoreGraphics
import Foundation

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count == 2, let owner = Int32(CommandLine.arguments[1]) else {
    fail("Expected the isolated app PID")
}
let deadline = Date().addingTimeInterval(65)
var samples = 0
var mainSamples = 0
while Date() < deadline {
    guard let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else {
        fail("Window metadata unavailable")
    }
    let owned = windows.filter {
        ($0[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == owner &&
        ($0[kCGWindowLayer as String] as? NSNumber)?.intValue == 0
    }
    guard owned.count <= 1 else { fail("Multiple visible app windows during startup") }
    for window in owned {
        guard let bounds = window[kCGWindowBounds as String] as? [String: Any],
              let width = bounds["Width"] as? NSNumber else { fail("Missing window bounds") }
        guard width.doubleValue > 900 else { fail("Startup status window became visible") }
        mainSamples += 1
    }
    samples += 1
    if mainSamples >= 40 {
        print("PASS: main window observed in 40 samples; no startup status window or multiple app windows in \(samples) samples")
        exit(0)
    }
    Thread.sleep(forTimeInterval: 0.05)
}
fail("Main window was not observed")
