import SwiftUI
import UIKit
import ImageIO
import UniformTypeIdentifiers

@MainActor
struct ReaderCaptureView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var library: BookshelfLibrary
    let pages: [ReaderPage]
    let start: Int

    @State private var count = 1
    @State private var preview: UIImage?
    @State private var busy = false
    @State private var saving = false
    @State private var retry = 0
    @State private var left = 0.0
    @State private var right = 1.0
    @State private var top = 0.0
    @State private var bottom = 1.0
    @State private var target: UUID?
    @State private var pickingFolder = false
    @State private var creatingFolder = false
    @State private var createAfterPicker = false
    @State private var name = ""
    @State private var export = CapturePNG(data: Data())
    @State private var exporting = false
    @State private var message: String?

    private var availableCount: Int { max(1, min(8, pages.count - start)) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if availableCount > 1 {
                        Stepper("Pages to capture: \(count)", value: $count, in: 1...availableCount)
                            .disabled(saving)
                    }
                    if let preview {
                        GeometryReader { geometry in
                            let width = geometry.size.width
                            let height = geometry.size.height
                            Image(uiImage: preview).resizable()
                                .overlay(alignment: .topLeading) {
                                    Rectangle().strokeBorder(.cyan, lineWidth: 2)
                                        .frame(width: width * (right - left), height: height * (bottom - top))
                                        .offset(x: width * left, y: height * top)
                                }
                        }
                        .aspectRatio(preview.size.width / preview.size.height, contentMode: .fit)
                        .frame(maxHeight: 480)
                        Group {
                            edge("Left", value: $left, limits: 0...max(0, right - 0.02))
                            edge("Right", value: $right, limits: min(1, left + 0.02)...1)
                            edge("Top", value: $top, limits: 0...max(0, bottom - 0.02))
                            edge("Bottom", value: $bottom, limits: min(1, top + 0.02)...1)
                        }
                        Button {
                            pickingFolder = true
                        } label: {
                            Label(library.catalog.shelves.first { $0.id == target }?.name ?? library.shelf.name,
                                  systemImage: "folder")
                        }
                        HStack {
                            Button("Save to Files") {
                                if let data = croppedPNG() {
                                    export = CapturePNG(data: data)
                                    exporting = true
                                }
                            }
                            Button("Add to Bookshelf") { Task { await saveToShelf() } }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(busy || saving || library.busy)
                    } else if busy {
                        ProgressView("Preparing capture")
                    } else {
                        Button("Retry capture") { retry += 1 }
                    }
                }
                .padding()
            }
            .navigationTitle("Reader capture")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task(id: "\(count)-\(retry)") {
                preview = nil
                busy = true
                do {
                    let image = try await Self.compose(Array(pages.dropFirst(start).prefix(count)))
                    try Task.checkCancellation()
                    preview = image
                    busy = false
                } catch is CancellationError {
                    // A different page count supersedes this preview.
                } catch {
                    if !Task.isCancelled {
                        message = error.localizedDescription
                        busy = false
                    }
                }
            }
            .sheet(isPresented: $pickingFolder, onDismiss: {
                guard createAfterPicker else { return }
                createAfterPicker = false
                creatingFolder = true
            }) {
                ShelfFolderPicker(library: library, title: "Capture destination", selectedID: target ?? library.shelf.id,
                                  createFolder: { createAfterPicker = true; pickingFolder = false }) { id in
                    target = id
                    pickingFolder = false
                }
            }
            .alert("New shelf", isPresented: $creatingFolder) {
                TextField("Name", text: $name)
                Button("Create") {
                    if let id = library.name(name, rename: false, selectNew: false) { target = id }
                    else { message = String(localized: "Enter a unique shelf name.") }
                    name = ""
                }
                Button("Cancel", role: .cancel) { name = "" }
            }
            .alert("Reader capture", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") { message = nil }
            } message: { Text(message ?? "") }
            .fileExporter(isPresented: $exporting, document: export, contentType: .png,
                          defaultFilename: "Umtomi-capture.png") { result in
                if case .failure(let error) = result { message = error.localizedDescription }
            }
        }
    }

    private func edge(_ title: LocalizedStringKey, value: Binding<Double>, limits: ClosedRange<Double>) -> some View {
        HStack {
            Text(title).frame(width: 70, alignment: .leading)
            Slider(value: Binding(get: { value.wrappedValue }, set: {
                value.wrappedValue = min(limits.upperBound, max(limits.lowerBound, $0))
            }), in: 0...1)
                .accessibilityLabel(Text(title))
        }
    }

    private func croppedPNG() -> Data? {
        guard let image = preview?.cgImage else { return nil }
        let width = Double(image.width)
        let height = Double(image.height)
        let rect = CGRect(x: width * left, y: height * top,
                          width: width * (right - left), height: height * (bottom - top)).integral
        return image.cropping(to: rect).flatMap { UIImage(cgImage: $0).pngData() }
    }

    private func saveToShelf() async {
        guard !busy, !saving, !library.busy, let data = croppedPNG() else { return }
        saving = true
        defer { saving = false }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("capture-\(UUID()).png")
        defer { try? FileManager.default.removeItem(at: url) }
        do {
            try data.write(to: url, options: .atomic)
            if await library.add([url], to: target) == 1 { dismiss() }
            else { message = library.error ?? String(localized: "Couldn't save the capture.") }
        } catch { message = error.localizedDescription }
    }

    private static func compose(_ pages: [ReaderPage]) async throws -> UIImage {
        var images: [CGImage] = []
        for page in pages {
            try Task.checkCancellation()
            let data = try await HitomiService.shared.imageData(for: page.url)
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 1600
                  ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
            images.append(image)
        }
        guard !images.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
        let ratios = images.map { CGFloat($0.height) / CGFloat($0.width) }
        let width = max(1, floor(min(1080, 9000 / ratios.reduce(0, +))))
        let heights = ratios.map { max(1, round(width * $0)) }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: heights.reduce(0, +)), format: format).image { _ in
            var y: CGFloat = 0
            for (image, height) in zip(images, heights) {
                UIImage(cgImage: image).draw(in: CGRect(x: 0, y: y, width: width, height: height))
                y += height
            }
        }
    }
}

private struct CapturePNG: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data

    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let bytes = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        data = bytes
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
