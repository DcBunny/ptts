import AVFoundation
import Flutter
import UIKit

private let sessionVideoOverlayChannel = "tiaosheng/session_video_overlay"
private let composeWithOverlayMethod = "composeWithOverlay"

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  private let composer = IOSSessionVideoOverlayComposer()

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)

    guard let registrar = engineBridge.pluginRegistry.registrar(
      forPlugin: "SessionVideoOverlayChannel"
    ) else {
      return
    }

    let channel = FlutterMethodChannel(
      name: sessionVideoOverlayChannel,
      binaryMessenger: registrar.messenger()
    )
    channel.setMethodCallHandler { [weak self] call, result in
      self?.handleMethodCall(call, result: result)
    }
  }

  private func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard call.method == composeWithOverlayMethod else {
      result(FlutterMethodNotImplemented)
      return
    }

    guard let args = call.arguments as? [String: Any],
      let inputPath = args["inputPath"] as? String
    else {
      result(
        FlutterError(
          code: "videoComposeFailed",
          message: "invalid_arguments",
          details: "inputPath is required and overlayItems must be valid"
        ))
      return
    }

    guard let overlayItems = parseOverlayItems(args["overlayItems"]) else {
      result(
        FlutterError(
          code: "videoComposeFailed",
          message: "invalid_arguments",
          details: "inputPath is required and overlayItems must be valid"
        ))
      return
    }

    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      guard let self else {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: "videoComposeFailed",
              message: "composer_unavailable",
              details: nil
            ))
        }
        return
      }

      do {
        let outputPath = try self.composer.compose(
          inputPath: inputPath,
          overlayItems: overlayItems
        )
        DispatchQueue.main.async {
          result(outputPath)
        }
      } catch let error as SessionVideoOverlayComposeError {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: error.code,
              message: error.message,
              details: error.detail
            ))
        }
      } catch {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: "videoComposeFailed",
              message: error.localizedDescription,
              details: nil
            ))
        }
      }
    }
  }

  private func parseOverlayItems(_ rawValue: Any?) -> [OverlayItem]? {
    guard let rawItems = rawValue as? [[String: Any]] else {
      return rawValue == nil ? [] : nil
    }

    var items: [OverlayItem] = []
    for item in rawItems {
      guard
        let text = item["text"] as? String,
        let startMs = item["startMs"] as? Int,
        let endMs = item["endMs"] as? Int,
        let positionName = item["position"] as? String,
        let styleName = item["style"] as? String,
        let position = OverlayPosition(rawValue: positionName),
        let style = OverlayStyle(rawValue: styleName)
      else {
        return nil
      }

      items.append(
        OverlayItem(
          text: text,
          startMs: Int64(startMs),
          endMs: Int64(endMs),
          position: position,
          style: style
        ))
    }
    return items
  }
}

private struct OverlayItem {
  let text: String
  let startMs: Int64
  let endMs: Int64
  let position: OverlayPosition
  let style: OverlayStyle
}

private enum OverlayPosition: String {
  case center
  case topLeft
  case topRight
  case bottomCenter
}

private enum OverlayStyle: String {
  case countdown
  case badge
  case subtitle
}

private struct SessionVideoOverlayComposeError: Error {
  let code: String
  let message: String
  let detail: String?
}

private final class IOSSessionVideoOverlayComposer {
  func compose(
    inputPath: String,
    overlayItems: [OverlayItem]
  ) throws -> String {
    let inputURL = URL(fileURLWithPath: inputPath)
    guard FileManager.default.fileExists(atPath: inputURL.path) else {
      throw SessionVideoOverlayComposeError(
        code: "videoUnavailable",
        message: "video file is missing",
        detail: inputPath
      )
    }

    let asset = AVAsset(url: inputURL)
    guard let sourceVideoTrack = asset.tracks(withMediaType: .video).first else {
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "video track is missing",
        detail: nil
      )
    }

    let composition = AVMutableComposition()
    guard
      let compositionVideoTrack = composition.addMutableTrack(
        withMediaType: .video,
        preferredTrackID: kCMPersistentTrackID_Invalid
      )
    else {
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "failed to create composition video track",
        detail: nil
      )
    }

    let fullRange = CMTimeRange(start: .zero, duration: asset.duration)
    do {
      try compositionVideoTrack.insertTimeRange(fullRange, of: sourceVideoTrack, at: .zero)
      if let sourceAudioTrack = asset.tracks(withMediaType: .audio).first,
        let compositionAudioTrack = composition.addMutableTrack(
          withMediaType: .audio,
          preferredTrackID: kCMPersistentTrackID_Invalid
        )
      {
        try compositionAudioTrack.insertTimeRange(fullRange, of: sourceAudioTrack, at: .zero)
      }
    } catch {
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "failed to build composition",
        detail: error.localizedDescription
      )
    }

    let renderSize = resolvedRenderSize(from: sourceVideoTrack)
    let durationSeconds = composition.duration.seconds
    if durationSeconds.isFinite == false || durationSeconds <= 0 {
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "invalid video duration",
        detail: nil
      )
    }

    let videoComposition = AVMutableVideoComposition()
    videoComposition.renderSize = renderSize
    videoComposition.frameDuration = frameDuration(for: sourceVideoTrack)
    videoComposition.instructions = [
      makeInstruction(
        for: compositionVideoTrack,
        sourceTrack: sourceVideoTrack,
        duration: composition.duration
      )
    ]

    let overlayLayers = buildOverlayLayers(
      renderSize: renderSize,
      durationSeconds: durationSeconds,
      overlayItems: overlayItems
    )
    videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
      postProcessingAsVideoLayer: overlayLayers.videoLayer,
      in: overlayLayers.parentLayer
    )

    guard let exportSession = AVAssetExportSession(
      asset: composition,
      presetName: AVAssetExportPresetHighestQuality
    ) else {
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "failed to create export session",
        detail: nil
      )
    }

    let outputFileType = try preferredOutputFileType(for: exportSession)
    let outputURL = buildOutputURL(for: outputFileType)
    if FileManager.default.fileExists(atPath: outputURL.path) {
      try? FileManager.default.removeItem(at: outputURL)
    }

    exportSession.outputURL = outputURL
    exportSession.outputFileType = outputFileType
    exportSession.shouldOptimizeForNetworkUse = true
    exportSession.videoComposition = videoComposition

    let semaphore = DispatchSemaphore(value: 0)
    exportSession.exportAsynchronously {
      semaphore.signal()
    }
    let waitResult = semaphore.wait(timeout: .now() + 180)
    if waitResult == .timedOut {
      exportSession.cancelExport()
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "compose timeout",
        detail: nil
      )
    }

    switch exportSession.status {
    case .completed:
      guard FileManager.default.fileExists(atPath: outputURL.path) else {
        throw SessionVideoOverlayComposeError(
          code: "videoComposeOutputMissing",
          message: "output file is missing",
          detail: outputURL.path
        )
      }
      return outputURL.path
    case .failed:
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: exportSession.error?.localizedDescription ?? "export failed",
        detail: outputURL.path
      )
    case .cancelled:
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "export cancelled",
        detail: nil
      )
    default:
      throw SessionVideoOverlayComposeError(
        code: "videoComposeFailed",
        message: "unexpected export status",
        detail: "status=\(exportSession.status.rawValue)"
      )
    }
  }

  private func preferredOutputFileType(for exportSession: AVAssetExportSession) throws -> AVFileType {
    let supportedFileTypes = exportSession.supportedFileTypes
    if supportedFileTypes.contains(.mp4) {
      return .mp4
    }
    if let firstSupportedFileType = supportedFileTypes.first {
      return firstSupportedFileType
    }
    throw SessionVideoOverlayComposeError(
      code: "videoComposeFailed",
      message: "no supported export file types",
      detail: nil
    )
  }

  private func buildOutputURL(for fileType: AVFileType) -> URL {
    let fileExtension = outputFileExtension(for: fileType)
    let filename =
      "jump_session_overlay_\(Int(Date().timeIntervalSince1970 * 1000)).\(fileExtension)"
    let baseDirectory =
      FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
      ?? FileManager.default.temporaryDirectory
    let outputDirectory = baseDirectory.appendingPathComponent(
      "session_videos",
      isDirectory: true
    )
    if FileManager.default.fileExists(atPath: outputDirectory.path) == false {
      try? FileManager.default.createDirectory(
        at: outputDirectory,
        withIntermediateDirectories: true
      )
    }
    return outputDirectory.appendingPathComponent(filename)
  }

  private func outputFileExtension(for fileType: AVFileType) -> String {
    switch fileType {
    case .mov:
      return "mov"
    case .m4v:
      return "m4v"
    default:
      return "mp4"
    }
  }

  private func resolvedRenderSize(from track: AVAssetTrack) -> CGSize {
    let transformed = track.naturalSize.applying(track.preferredTransform)
    let width = max(abs(transformed.width), 1)
    let height = max(abs(transformed.height), 1)
    return CGSize(width: width, height: height)
  }

  private func frameDuration(for track: AVAssetTrack) -> CMTime {
    let nominalFrameRate = track.nominalFrameRate
    if nominalFrameRate > 0 {
      return CMTime(value: 1, timescale: CMTimeScale(nominalFrameRate.rounded()))
    }
    return CMTime(value: 1, timescale: 30)
  }

  private func makeInstruction(
    for compositionTrack: AVCompositionTrack,
    sourceTrack: AVAssetTrack,
    duration: CMTime
  ) -> AVMutableVideoCompositionInstruction {
    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = CMTimeRange(start: .zero, duration: duration)

    let layerInstruction = AVMutableVideoCompositionLayerInstruction(
      assetTrack: compositionTrack
    )
    layerInstruction.setTransform(sourceTrack.preferredTransform, at: .zero)

    instruction.layerInstructions = [layerInstruction]
    return instruction
  }

  private func buildOverlayLayers(
    renderSize: CGSize,
    durationSeconds: Double,
    overlayItems: [OverlayItem]
  ) -> (videoLayer: CALayer, parentLayer: CALayer) {
    let parentLayer = CALayer()
    parentLayer.frame = CGRect(origin: .zero, size: renderSize)

    let videoLayer = CALayer()
    videoLayer.frame = parentLayer.frame
    parentLayer.addSublayer(videoLayer)

    addOverlayLayers(
      parentLayer: parentLayer,
      renderSize: renderSize,
      durationSeconds: durationSeconds,
      overlayItems: overlayItems
    )

    return (videoLayer, parentLayer)
  }

  private func addOverlayLayers(
    parentLayer: CALayer,
    renderSize: CGSize,
    durationSeconds: Double,
    overlayItems: [OverlayItem]
  ) {
    let maxDurationMs = Int64(durationSeconds * 1000.0)
    for item in overlayItems {
      let startMs = max(item.startMs, 0)
      let endMs = min(item.endMs, maxDurationMs)
      guard endMs > startMs else {
        continue
      }

      let layer = makeOverlayLayer(renderSize: renderSize, item: item)
      parentLayer.addSublayer(layer)
      addVisibilityAnimation(
        layer: layer,
        begin: Double(startMs) / 1000.0,
        duration: Double(endMs - startMs) / 1000.0
      )
    }
  }

  private func makeOverlayLayer(
    renderSize: CGSize,
    item: OverlayItem
  ) -> CALayer {
    switch item.style {
    case .countdown:
      return makeCountdownLayer(renderSize: renderSize, text: item.text)
    case .badge:
      return makeBadgeLayer(
        renderSize: renderSize,
        text: item.text,
        position: item.position
      )
    case .subtitle:
      return makeSubtitleLayer(renderSize: renderSize, text: item.text)
    }
  }

  private func makeCountdownLayer(renderSize: CGSize, text: String) -> CALayer {
    let fontSize = min(renderSize.width, renderSize.height) * 0.26
    let layerHeight = fontSize * 1.4
    let layerY = (renderSize.height - layerHeight) * 0.5

    let textLayer = CATextLayer()
    textLayer.contentsScale = UIScreen.main.scale
    textLayer.frame = CGRect(x: 0, y: layerY, width: renderSize.width, height: layerHeight)
    textLayer.string = NSAttributedString(
      string: text,
      attributes: [
        .font: UIFont.systemFont(ofSize: fontSize, weight: .heavy),
        .foregroundColor: UIColor.white,
      ]
    )
    textLayer.alignmentMode = .center
    textLayer.opacity = 0
    textLayer.shadowColor = UIColor.black.cgColor
    textLayer.shadowOpacity = 0.75
    textLayer.shadowRadius = 10
    textLayer.shadowOffset = .zero
    return textLayer
  }

  private func makeBadgeLayer(
    renderSize: CGSize,
    text: String,
    position: OverlayPosition
  ) -> CALayer {
    let badgeWidth = min(renderSize.width * 0.34, 220)
    let badgeHeight = min(renderSize.height * 0.15, 118)
    let x: CGFloat
    switch position {
    case .topLeft:
      x = 24
    case .center, .topRight, .bottomCenter:
      x = renderSize.width - badgeWidth - 24
    }
    let y = 28.0

    let badgeLayer = CALayer()
    badgeLayer.frame = CGRect(x: x, y: y, width: badgeWidth, height: badgeHeight)
    badgeLayer.backgroundColor = UIColor(white: 0, alpha: 0.62).cgColor
    badgeLayer.cornerRadius = 22
    badgeLayer.opacity = 0

    let textLayer = makeCenteredTextLayer(
      frame: badgeLayer.bounds,
      text: text,
      font: UIFont.systemFont(ofSize: 58, weight: .bold)
    )
    badgeLayer.addSublayer(textLayer)
    return badgeLayer
  }

  private func makeSubtitleLayer(renderSize: CGSize, text: String) -> CALayer {
    let layerWidth = renderSize.width - 48
    let layerHeight = min(max(renderSize.height * 0.09, 56), 92)
    let x = 24.0
    let y = renderSize.height - layerHeight - 72

    let subtitleLayer = CALayer()
    subtitleLayer.frame = CGRect(x: x, y: y, width: layerWidth, height: layerHeight)
    subtitleLayer.backgroundColor = UIColor(white: 0, alpha: 0.58).cgColor
    subtitleLayer.cornerRadius = 18
    subtitleLayer.opacity = 0

    let fontSize = min(renderSize.width * 0.055, 28)
    let textLayer = makeCenteredTextLayer(
      frame: subtitleLayer.bounds,
      text: text,
      font: UIFont.systemFont(ofSize: fontSize, weight: .semibold)
    )
    subtitleLayer.addSublayer(textLayer)
    return subtitleLayer
  }

  private func makeCenteredTextLayer(
    frame: CGRect,
    text: String,
    font: UIFont
  ) -> CATextLayer {
    let textLayer = CATextLayer()
    textLayer.contentsScale = UIScreen.main.scale
    textLayer.frame = frame
    textLayer.string = NSAttributedString(
      string: text,
      attributes: [
        .font: font,
        .foregroundColor: UIColor.white,
      ]
    )
    textLayer.alignmentMode = .center
    textLayer.truncationMode = .end
    textLayer.isWrapped = false
    return textLayer
  }

  private func addVisibilityAnimation(layer: CALayer, begin: Double, duration: Double) {
    guard duration > 0 else {
      return
    }
    let animation = CABasicAnimation(keyPath: "opacity")
    animation.fromValue = 1
    animation.toValue = 1
    animation.beginTime = AVCoreAnimationBeginTimeAtZero + begin
    animation.duration = duration
    animation.fillMode = .removed
    animation.isRemovedOnCompletion = true
    layer.add(animation, forKey: "visible_\(begin)")
  }
}
