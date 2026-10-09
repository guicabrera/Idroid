import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

/// What the "Start pairing" QR code encodes: `idroid://pair?relay=<url>&code=<6 digits>`.
/// The Android app claims the code as soon as it scans this — either with its
/// in-app scanner or through the system camera, which opens the deep link.
func pairingLink(relayBaseURL: String, code: String) -> String {
    var components = URLComponents()
    components.scheme = "idroid"
    components.host = "pair"
    components.queryItems = [
        URLQueryItem(name: "relay", value: relayBaseURL),
        URLQueryItem(name: "code", value: code),
    ]
    return components.string ?? ""
}

struct PairingQRCode: View {
    let payload: String

    var body: some View {
        if let image = Self.render(payload) {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .frame(width: 200, height: 200)
                // White quiet zone so it still scans in dark mode.
                .padding(12)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private static func render(_ payload: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else {
            return nil
        }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
