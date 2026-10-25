import AppKit

guard CommandLine.arguments.count == 2 else {
    fatalError("Usage: swift render-dougle-icon.swift output.png")
}
let size = 1024
guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
    let context = NSGraphicsContext(bitmapImageRep: bitmap),
    let font = NSFont(name: "AvenirNext-Medium", size: 222) else {
    fatalError("Avenir Next and an RGBA bitmap context are required")
}
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
NSColor.clear.setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()
let tile = NSBezierPath(roundedRect: NSRect(x: 80, y: 80, width: 864, height: 864),
    xRadius: 192, yRadius: 192)
NSColor(calibratedRed: 0.99, green: 0.995, blue: 1, alpha: 1).setFill()
tile.fill()
NSColor(calibratedRed: 0.88, green: 0.91, blue: 0.95, alpha: 1).setStroke()
tile.lineWidth = 3
tile.stroke()
let colors: [UInt32] = [0x3478db, 0xdc6454, 0xe5ab32, 0x36966b, 0x3478db, 0xdc6454]
let wordmark = NSMutableAttributedString(string: "Dougle", attributes: [.font: font, .kern: -11])
for (index, color) in colors.enumerated() {
    wordmark.addAttribute(.foregroundColor, value: NSColor(
        calibratedRed: CGFloat((color >> 16) & 255) / 255,
        green: CGFloat((color >> 8) & 255) / 255,
        blue: CGFloat(color & 255) / 255, alpha: 1), range: NSRange(location: index, length: 1))
}
let textSize = wordmark.size()
let origin = NSPoint(x: (CGFloat(size) - textSize.width) / 2,
    y: (CGFloat(size) - textSize.height) / 2 + 12)
wordmark.addAttribute(.foregroundColor, value: NSColor.clear, range: NSRange(location: 1, length: 1))
wordmark.draw(at: origin)
let prefixWidth = wordmark.attributedSubstring(from: NSRange(location: 0, length: 1)).size().width
let faceRect = NSRect(x: origin.x + prefixWidth - 7, y: origin.y + 80, width: 133, height: 133)
let face = NSBezierPath(ovalIn: faceRect)
NSGraphicsContext.saveGraphicsState()
face.addClip()
NSColor.white.setFill()
faceRect.fill()
guard let mascot = NSImage(contentsOfFile: "frontend/src/assets/dougle-mascot-transparent.png") else {
    fatalError("Dougle mascot asset is required")
}
mascot.draw(in: NSRect(x: faceRect.minX - faceRect.width * 0.2,
    y: faceRect.minY - faceRect.height * 0.32,
    width: faceRect.width * 1.4, height: faceRect.height * 1.4))
NSGraphicsContext.restoreGraphicsState()
NSColor(calibratedRed: 0.86, green: 0.39, blue: 0.33, alpha: 1).setStroke()
face.lineWidth = 4
face.stroke()
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else {
    fatalError("PNG rendering failed")
}
try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]), options: .atomic)
