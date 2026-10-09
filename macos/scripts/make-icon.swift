// Draws the Idroid app icon — the same mark as the Android app
// (android/app/src/main/res/drawable/ic_logo.xml): two opposite arrows on an
// indigo-to-violet gradient — at 1024×1024 on the macOS icon grid.
//
//   swift scripts/make-icon.swift <out.png>
//
// bundle.sh turns the PNG into Idroid.icns.
import AppKit

let size: CGFloat = 1024
let out = CommandLine.arguments.dropFirst().first ?? "icon.png"

let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size),
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let ctx = NSGraphicsContext.current!.cgContext

// Apple's icon grid: an 824pt rounded square centered in 1024, with a soft shadow.
let inset: CGFloat = 100
let body = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
let shape = CGPath(roundedRect: body, cornerWidth: 186, cornerHeight: 186, transform: nil)

ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 28, color: NSColor.black.withAlphaComponent(0.3).cgColor)
ctx.addPath(shape)
ctx.setFillColor(NSColor.black.cgColor)
ctx.fillPath()
ctx.restoreGState()

ctx.saveGState()
ctx.addPath(shape)
ctx.clip()
let gradient = CGGradient(
    colorsSpace: CGColorSpaceCreateDeviceRGB(),
    colors: [
        NSColor(srgbRed: 0x4F / 255, green: 0x46 / 255, blue: 0xE5 / 255, alpha: 1).cgColor,
        NSColor(srgbRed: 0x8B / 255, green: 0x5C / 255, blue: 0xF6 / 255, alpha: 1).cgColor,
    ] as CFArray,
    locations: [0, 1]
)!
// Top-left to bottom-right, like the Android drawable (CG's y axis points up).
ctx.drawLinearGradient(gradient, start: CGPoint(x: body.minX, y: body.maxY), end: CGPoint(x: body.maxX, y: body.minY), options: [])

// A faint top highlight for depth.
let highlight = CGGradient(
    colorsSpace: CGColorSpaceCreateDeviceRGB(),
    colors: [NSColor.white.withAlphaComponent(0.18).cgColor, NSColor.white.withAlphaComponent(0).cgColor] as CFArray,
    locations: [0, 1]
)!
ctx.drawLinearGradient(highlight, start: CGPoint(x: 0, y: body.maxY), end: CGPoint(x: 0, y: body.midY), options: [])
ctx.restoreGState()

// The arrows, in ic_logo.xml's 108-unit coordinates mapped onto the body.
let unit = body.width / 108
func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
    CGPoint(x: body.minX + x * unit, y: body.maxY - y * unit)
}
func arrow(_ points: [[(CGFloat, CGFloat)]], alpha: CGFloat) {
    ctx.setStrokeColor(NSColor.white.withAlphaComponent(alpha).cgColor)
    ctx.setLineWidth(8.5 * unit)
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)
    for segment in points {
        ctx.move(to: point(segment[0].0, segment[0].1))
        for p in segment.dropFirst() { ctx.addLine(to: point(p.0, p.1)) }
    }
    ctx.strokePath()
}
arrow([[(30, 42), (76, 42)], [(64, 30), (76, 42), (64, 54)]], alpha: 1)
arrow([[(78, 66), (32, 66)], [(44, 54), (32, 66), (44, 78)]], alpha: 0.8)

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
