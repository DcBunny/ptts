import CoreVideo
import Flutter
import MediaPipeTasksVision
import QuartzCore
import UIKit

private let jumpRopePoseAnalyzerChannelName = "tiaosheng/jump_rope_pose_analyzer"
private let jumpRopePoseModelAssetPath = "assets/models/pose_landmarker_lite.task"

final class JumpRopePoseAnalyzerChannel: NSObject {
  private let channel: FlutterMethodChannel
  private let queue = DispatchQueue(label: "tiaosheng.jump_rope_pose_analyzer")
  private let modelPath: String
  private var poseLandmarker: PoseLandmarker?
  private var isSessionStarted = false

  init(registrar: FlutterPluginRegistrar) throws {
    guard let resourcePath = Bundle.main.resourcePath else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterInitFailed",
        message: "bundle_resource_path_missing"
      )
    }
    modelPath = resourcePath.appending("/\(registrar.lookupKey(forAsset: jumpRopePoseModelAssetPath))")
    channel = FlutterMethodChannel(
      name: jumpRopePoseAnalyzerChannelName,
      binaryMessenger: registrar.messenger()
    )
    super.init()
    channel.setMethodCallHandler(handleMethodCall)
  }

  func dispose() {
    channel.setMethodCallHandler(nil)
    poseLandmarker = nil
    isSessionStarted = false
  }

  private func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    queue.async { [weak self] in
      guard let self else {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: "jumpCounterRuntimeFailed",
              message: "pose_channel_unavailable",
              details: nil
            ))
        }
        return
      }

      do {
        let payload = try self.perform(call: call)
        DispatchQueue.main.async {
          result(payload)
        }
      } catch let error as PoseAnalyzerChannelError {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: error.code,
              message: error.message,
              details: error.details
            ))
        }
      } catch {
        DispatchQueue.main.async {
          result(
            FlutterError(
              code: "jumpCounterRuntimeFailed",
              message: error.localizedDescription,
              details: nil
            ))
        }
      }
    }
  }

  private func perform(call: FlutterMethodCall) throws -> Any? {
    switch call.method {
    case "initialize":
      try initializeLandmarker()
      return nil
    case "startSession":
      _ = try requireLandmarker()
      isSessionStarted = true
      return nil
    case "analyzeFrame":
      try requireStartedSession()
      let frame = try IOSPoseFrame(call: call)
      return try analyzeFrame(frame)
    case "stopSession":
      isSessionStarted = false
      return nil
    case "dispose":
      poseLandmarker = nil
      isSessionStarted = false
      return nil
    default:
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "unsupported_pose_method:\(call.method)"
      )
    }
  }

  private func initializeLandmarker() throws {
    if poseLandmarker != nil {
      return
    }

    let options = PoseLandmarkerOptions()
    options.baseOptions.modelAssetPath = modelPath
    options.runningMode = .video
    options.minPoseDetectionConfidence = 0.5
    options.minPosePresenceConfidence = 0.5
    options.minTrackingConfidence = 0.5
    options.numPoses = 2
    poseLandmarker = try PoseLandmarker(options: options)
  }

  private func analyzeFrame(_ frame: IOSPoseFrame) throws -> [String: Any] {
    let startedAt = CACurrentMediaTime()
    let image = try frame.toMPImage()
    let result = try requireLandmarker().detect(
      videoFrame: image,
      timestampInMilliseconds: frame.timestampMs
    )
    let latencyMs = Int((CACurrentMediaTime() - startedAt) * 1000)
    return serialize(result: result, timestampMs: frame.timestampMs, latencyMs: latencyMs)
  }

  private func serialize(
    result: PoseLandmarkerResult,
    timestampMs: Int,
    latencyMs: Int
  ) -> [String: Any] {
    return [
      "timestampMs": timestampMs,
      "analysisLatencyMs": latencyMs,
      "poses": result.landmarks.map { pose in
        [
          "landmarks": pose.map { landmark in
            [
              "x": landmark.x,
              "y": landmark.y,
              "z": landmark.z,
              // iOS may return nil for optional visibility/presence in some cases.
              // Use 1.0 fallback to avoid treating every frame as low-confidence.
              "visibility": landmark.visibility ?? 1,
              "presence": landmark.presence ?? 1,
            ]
          },
        ]
      },
    ]
  }

  private func requireLandmarker() throws -> PoseLandmarker {
    guard let poseLandmarker else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterInitFailed",
        message: "pose_landmarker_not_initialized"
      )
    }
    return poseLandmarker
  }

  private func requireStartedSession() throws {
    guard isSessionStarted else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "pose_session_not_started"
      )
    }
  }
}

private struct IOSPoseFrame {
  init(call: FlutterMethodCall) throws {
    guard
      let arguments = call.arguments as? [String: Any],
      let width = arguments["width"] as? Int,
      let height = arguments["height"] as? Int,
      let rotationDegrees = arguments["rotationDegrees"] as? Int,
      let timestampMs = arguments["timestampMs"] as? Int,
      let format = arguments["format"] as? String,
      let rawPlanes = arguments["planes"] as? [[String: Any]]
    else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "invalid_ios_frame_arguments"
      )
    }

    self.width = width
    self.height = height
    self.rotationDegrees = rotationDegrees
    self.timestampMs = timestampMs
    self.format = format
    planes = rawPlanes.map {
      IOSPosePlane(
        bytes: ($0["bytes"] as? FlutterStandardTypedData)?.data ?? Data(),
        bytesPerRow: ($0["bytesPerRow"] as? Int) ?? 0
      )
    }
  }

  let width: Int
  let height: Int
  let rotationDegrees: Int
  let timestampMs: Int
  let format: String
  let planes: [IOSPosePlane]

  func toMPImage() throws -> MPImage {
    guard format == "bgra8888" else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "unsupported_ios_frame_format:\(format)"
      )
    }
    guard let plane = planes.first else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "missing_ios_frame_plane"
      )
    }
    let pixelBuffer = try plane.makePixelBuffer(width: width, height: height)
    return try MPImage(
      pixelBuffer: pixelBuffer,
      orientation: UIImage.Orientation(rotationDegrees: rotationDegrees)
    )
  }
}

private struct IOSPosePlane {
  let bytes: Data
  let bytesPerRow: Int

  func makePixelBuffer(width: Int, height: Int) throws -> CVPixelBuffer {
    let attributes = [
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true,
      kCVPixelBufferIOSurfacePropertiesKey: [:],
    ] as CFDictionary
    var pixelBuffer: CVPixelBuffer?
    let status = CVPixelBufferCreate(
      kCFAllocatorDefault,
      width,
      height,
      kCVPixelFormatType_32BGRA,
      attributes,
      &pixelBuffer
    )
    guard status == kCVReturnSuccess, let pixelBuffer else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "failed_to_create_pixel_buffer"
      )
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    defer {
      CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
    }

    guard let destinationBaseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
      throw PoseAnalyzerChannelError(
        code: "jumpCounterRuntimeFailed",
        message: "pixel_buffer_base_address_missing"
      )
    }

    let destinationStride = CVPixelBufferGetBytesPerRow(pixelBuffer)
    bytes.withUnsafeBytes { sourcePointer in
      guard let sourceBaseAddress = sourcePointer.baseAddress else {
        return
      }
      for row in 0..<height {
        let sourceRow = sourceBaseAddress.advanced(by: row * bytesPerRow)
        let destinationRow = destinationBaseAddress.advanced(by: row * destinationStride)
        memcpy(destinationRow, sourceRow, min(bytesPerRow, destinationStride))
      }
    }
    return pixelBuffer
  }
}

private struct PoseAnalyzerChannelError: Error {
  let code: String
  let message: String
  let details: String? = nil
}

private extension UIImage.Orientation {
  init(rotationDegrees: Int) {
    switch rotationDegrees {
    case 90:
      self = .right
    case 180:
      self = .down
    case 270:
      self = .left
    default:
      self = .up
    }
  }
}
