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
wordmark.draw(at: NSPoint(x: (CGFloat(size) - textSize.width) / 2,
    y: (CGFloat(size) - textSize.height) / 2 + 12))
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else {
    fatalError("PNG rendering failed")
}
try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]), options: .atomic)
