import AVFoundation
import SwiftUI

/// Сканер QR камерой (AVFoundation) — аналог ZXing-сканера на Android.
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let c = ScannerController()
        c.onCode = onCode
        return c
    }
    func updateUIViewController(_: ScannerController, context _: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var delivered = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
                let label = UILabel()
                label.text = NSLocalizedString("import_no_qr", comment: "")
                label.textColor = .white; label.numberOfLines = 0; label.textAlignment = .center
                label.frame = view.bounds.insetBy(dx: 24, dy: 0); label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.addSubview(label)
                return
            }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.layer.bounds
            view.layer.addSublayer(preview)

            let hint = UILabel()
            hint.text = NSLocalizedString("qr_scan_prompt", comment: "")
            hint.textColor = .white; hint.numberOfLines = 0; hint.textAlignment = .center
            hint.backgroundColor = UIColor.black.withAlphaComponent(0.5)
            hint.frame = CGRect(x: 16, y: view.bounds.height - 140, width: view.bounds.width - 32, height: 64)
            hint.autoresizingMask = [.flexibleWidth, .flexibleTopMargin]
            view.addSubview(hint)
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            session.stopRunning()
        }

        func metadataOutput(_: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from _: AVCaptureConnection) {
            guard !delivered, let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            delivered = true
            session.stopRunning()
            onCode?(code)
        }
    }
}
