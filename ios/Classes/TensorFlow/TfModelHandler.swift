//
//  TfModelHandler.swift
//  flutter_anycam
//
//  Carrega modelos TFLite, fila de inferência e callback `processTfOutput` para o Dart.
//

import Foundation
import TensorFlowLite
import Flutter

final class TfModelHandler {
    static let shared = TfModelHandler()

    /// Dart must answer processTfOutput within this time, or the inference returns no result.
    private static let processOutputTimeout: DispatchTimeInterval = .seconds(10)

    private var channel: FlutterMethodChannel?
    private var assetLookup: ((String) -> String)?

    /// Serial: a TFLite interpreter is not thread-safe.
    private let inferQueue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.tf.infer", qos: .userInitiated)
    private var models: [String: LoadedModel] = [:]
    private let modelsLock = NSLock()

    private var results: [String: InferenceResult] = [:]
    private let resultsLock = NSLock()

    private init() {}

    func configure(channel: FlutterMethodChannel, assetLookup: @escaping (String) -> String) {
        self.channel = channel
        self.assetLookup = assetLookup
    }

    // MARK: - Modelo

    func loadModel(assetPath: String, key: String, delegate: String, threads: Int) throws {
        guard let lookup = assetLookup else {
            throw NSError(domain: "TfModelHandler", code: 0, userInfo: [NSLocalizedDescriptionKey: "Plugin não configurado"])
        }
        let mapped = lookup(assetPath)
        guard let path = resolveAssetPath(mappedKey: mapped) else {
            throw NSError(domain: "TfModelHandler", code: 1, userInfo: [NSLocalizedDescriptionKey: "Modelo não encontrado: \(assetPath)"])
        }

        var options = Interpreter.Options()
        options.threadCount = max(1, threads)
        // No GPU/NNAPI on iOS: every accelerated request maps to XNNPack, with a plain CPU
        // fallback when the model has ops XNNPack cannot take.
        let mode = delegate.lowercased()
        options.isXNNPackEnabled = mode != "cpu" && mode != "none"

        func makeInterpreter(_ opts: Interpreter.Options) throws -> Interpreter {
            let it = try Interpreter(modelPath: path, options: opts)
            try it.allocateTensors()
            return it
        }
        let interpreter: Interpreter
        if options.isXNNPackEnabled, let accelerated = try? makeInterpreter(options) {
            interpreter = accelerated
        } else {
            options.isXNNPackEnabled = false
            interpreter = try makeInterpreter(options)
        }

        let inTensor = try interpreter.input(at: 0)
        let inMeta = TensorMeta(tensor: inTensor)

        var outMetas: [TensorMeta] = []
        for i in 0..<interpreter.outputTensorCount {
            outMetas.append(TensorMeta(tensor: try interpreter.output(at: i)))
        }

        let loaded = LoadedModel(
            key: key,
            interpreter: interpreter,
            inputMeta: inMeta,
            outputMetas: outMetas
        )

        modelsLock.lock()
        models[key] = loaded
        modelsLock.unlock()
    }

    func disposeModel(key: String) {
        modelsLock.lock()
        models.removeValue(forKey: key)
        modelsLock.unlock()

        resultsLock.lock()
        let owned = results.values.filter { $0.modelKey == key }
        resultsLock.unlock()
        for r in owned {
            closeInferenceResult(id: r.id)
        }
    }

    func disposeAll() {
        modelsLock.lock()
        models.removeAll()
        modelsLock.unlock()

        resultsLock.lock()
        let all = Array(results.keys)
        resultsLock.unlock()
        for id in all {
            closeInferenceResult(id: id)
        }
    }

    // MARK: - Inferência

    func runInference(
        arguments: [String: Any],
        result flutterResult: @escaping FlutterResult
    ) {
        inferQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                let out = try self.performInference(arguments: arguments)
                DispatchQueue.main.async {
                    flutterResult(out)
                }
            } catch {
                DispatchQueue.main.async {
                    flutterResult(FlutterError(code: "TfModelHandlerError", message: error.localizedDescription, details: nil))
                }
            }
        }
    }

    private func performInference(arguments: [String: Any]) throws -> [[String: Any]] {
        guard let modelKey = arguments["modelKey"] as? String else {
            throw NSError(domain: "TfModelHandler", code: 2, userInfo: [NSLocalizedDescriptionKey: "modelKey em falta"])
        }
        modelsLock.lock()
        let loadedModel = models[modelKey]
        modelsLock.unlock()
        guard let loaded = loadedModel else {
            throw NSError(domain: "TfModelHandler", code: 3, userInfo: [NSLocalizedDescriptionKey: "Modelo não carregado"])
        }

        let normalize = (arguments["normalize"] as? String) ?? "none"
        let filter = (arguments["filter"] as? NSNumber)?.intValue ?? 0

        var inputSize: CGSize?
        if let sz = arguments["inputSize"] as? [String: Any],
           let w = (sz["width"] as? NSNumber)?.intValue,
           let h = (sz["height"] as? NSNumber)?.intValue {
            inputSize = CGSize(width: w, height: h)
        }

        guard let frameMap = arguments["inputFrame"] as? [String: Any],
              let frameId = frameMap["id"] as? String else {
            throw NSError(domain: "TfModelHandler", code: 4, userInfo: [NSLocalizedDescriptionKey: "inputFrame inválido"])
        }

        let prepared = try TfFrameHandler.shared.prepareInputFrame(
            inputFrameId: frameId,
            inputSize: inputSize,
            filter: filter
        )
        // Results hold their own pins; this call's ownership of the frame always ends here.
        defer { TfFrameHandler.shared.closeFrame(prepared.frameId) }

        let normalized = try TfFrameNormalizer.shared.normalize(
            bgra: prepared.bgra,
            width: prepared.width,
            height: prepared.height,
            normalize: normalize,
            inputKind: loaded.inputMeta.elementKind,
            invScale: loaded.inputMeta.invScale,
            zeroPoint: loaded.inputMeta.zeroPoint
        )

        if normalized.count != loaded.inputMeta.byteCount {
            throw NSError(domain: "TfModelHandler", code: 5, userInfo: [NSLocalizedDescriptionKey: "Tamanho do input (\(normalized.count)) ≠ esperado (\(loaded.inputMeta.byteCount))"])
        }

        let interpreter = loaded.interpreter
        try interpreter.copy(normalized, toInputAt: 0)
        try interpreter.invoke()

        var outs: [NSNumber: Any] = [:]
        for i in 0..<interpreter.outputTensorCount {
            let tensor = try interpreter.output(at: i)
            let shape = tensor.shape.dimensions
            let flat = try Self.readFloatOutput(tensor: tensor, meta: loaded.outputMetas[i])
            outs[NSNumber(value: i)] = Self.reshape(flat: flat, shape: shape, offset: 0)
        }

        let processed = invokeProcessTfOutput(modelKey: modelKey, outputs: outs)

        modelsLock.lock()
        let stillLoaded = models[modelKey] === loaded
        modelsLock.unlock()
        guard stillLoaded else { return [] }

        var response: [[String: Any]] = []
        for item in processed {
            guard TfFrameHandler.shared.pin(prepared.frameId) else { break }
            let inf = InferenceResult(modelKey: modelKey, mainFrameId: prepared.frameId, output: item)
            resultsLock.lock()
            results[inf.id] = inf
            resultsLock.unlock()
            response.append(inf.toFlutterMap())
        }
        return response
    }

    private func inferenceResult(byId inferenceId: String) -> InferenceResult? {
        resultsLock.lock()
        defer { resultsLock.unlock() }
        return results[inferenceId]
    }

    func mainFrameId(for inferenceId: String) -> String? {
        inferenceResult(byId: inferenceId)?.mainFrameId
    }

    func boxMap(for inferenceId: String) -> [String: Any]? {
        guard let o = inferenceResult(byId: inferenceId)?.output else { return nil }
        return o["box"] as? [String: Any]
    }

    private func derivedFrameMap(inferenceId: String, key: String, enableScale: Bool) -> [String: Any]? {
        guard let res = inferenceResult(byId: inferenceId),
              let box = res.output["box"] as? [String: Any] else { return nil }
        res.lock.lock()
        defer { res.lock.unlock() }
        if res.closed { return nil }
        if let eid = res.derivedFrameIds[key], let f = TfFrameHandler.shared.getFrameById(eid) {
            return TfFrameHandler.shared.toFrameMap(f)
        }
        guard let f = TfFrameHandler.shared.newFrameCroppedById(frameId: res.mainFrameId, box: box, enableScale: enableScale) else {
            return nil
        }
        res.derivedFrameIds[key] = f.id
        return TfFrameHandler.shared.toFrameMap(f)
    }

    func getScaledCroppedFrameMap(inferenceId: String) -> [String: Any]? {
        derivedFrameMap(inferenceId: inferenceId, key: InferenceResult.scaledCroppedKey, enableScale: true)
    }

    func getCroppedFrameMap(inferenceId: String) -> [String: Any]? {
        derivedFrameMap(inferenceId: inferenceId, key: InferenceResult.croppedKey, enableScale: false)
    }

    /// Idempotent: releases the derived crops and the result's pin on the inference frame.
    func closeInferenceResult(id: String) {
        resultsLock.lock()
        let res = results.removeValue(forKey: id)
        resultsLock.unlock()
        guard let r = res else { return }

        r.lock.lock()
        let wasClosed = r.closed
        r.closed = true
        let derived = Array(r.derivedFrameIds.values)
        r.derivedFrameIds.removeAll()
        r.lock.unlock()
        guard !wasClosed else { return }

        for fid in derived {
            TfFrameHandler.shared.closeFrame(fid)
        }
        TfFrameHandler.shared.unpin(r.mainFrameId)
    }

    func frameMapForInference(id: String, kind: FrameKind) -> [String: Any]? {
        switch kind {
        case .scaledCropped:
            return getScaledCroppedFrameMap(inferenceId: id)
        case .cropped:
            return getCroppedFrameMap(inferenceId: id)
        case .inference:
            guard let mainId = mainFrameId(for: id),
                  let f = TfFrameHandler.shared.getFrameById(mainId) else { return nil }
            return TfFrameHandler.shared.toFrameMap(f)
        case .raw:
            // The frame the inference started from, skipping the internal letterbox crop.
            guard let mid = mainFrameId(for: id), var parent = TfFrameHandler.shared.parentOf(mid) else {
                return nil
            }
            if parent.closeWhenChildClosed, let up = TfFrameHandler.shared.parentOf(parent.id) {
                parent = up
            }
            return TfFrameHandler.shared.toFrameMap(parent)
        }
    }

    enum FrameKind {
        case scaledCropped, cropped, inference, raw
    }

    // MARK: - Dart

    /// Blocks the inference queue (never main) until Dart answers or the timeout expires.
    private func invokeProcessTfOutput(modelKey: String, outputs: [NSNumber: Any]) -> [[String: Any]] {
        guard let ch = channel else { return [] }
        let sem = DispatchSemaphore(value: 0)
        let box = ReplyBox()
        DispatchQueue.main.async {
            ch.invokeMethod("processTfOutput", arguments: ["modelKey": modelKey, "output": outputs]) { result in
                var decoded: [[String: Any]] = []
                if let list = result as? [[String: Any]] {
                    decoded = list
                } else if let list = result as? [Any] {
                    decoded = list.compactMap { $0 as? [String: Any] }
                }
                box.set(decoded)
                sem.signal()
            }
        }
        if sem.wait(timeout: .now() + Self.processOutputTimeout) == .timedOut {
            NSLog("[flutter_anycam] processTfOutput timed out for model \(modelKey)")
            return []
        }
        return box.get()
    }

    // MARK: - Tensor helpers

    static func readFloatOutput(tensor: Tensor, meta: TensorMeta) throws -> [Float] {
        let data = tensor.data
        switch tensor.dataType {
        case .float32:
            let n = data.count / MemoryLayout<Float32>.size
            return data.withUnsafeBytes { raw in
                Array(raw.bindMemory(to: Float32.self).prefix(n))
            }
        case .float64:
            let n = data.count / MemoryLayout<Float64>.size
            return data.withUnsafeBytes { raw in
                raw.bindMemory(to: Float64.self).prefix(n).map { Float($0) }
            }
        case .uInt8:
            let scale = meta.scale
            let zp = Float(meta.zeroPoint)
            if scale != 1 || zp != 0 {
                return data.map { (Float($0) - zp) * scale }
            }
            return data.map { Float($0) }
        case .int16:
            let n = data.count / MemoryLayout<Int16>.size
            return data.withUnsafeBytes { raw in
                raw.bindMemory(to: Int16.self).prefix(n).map { Float($0) }
            }
        case .int32:
            let n = data.count / MemoryLayout<Int32>.size
            return data.withUnsafeBytes { raw in
                raw.bindMemory(to: Int32.self).prefix(n).map { Float($0) }
            }
        case .int64:
            let n = data.count / MemoryLayout<Int64>.size
            return data.withUnsafeBytes { raw in
                raw.bindMemory(to: Int64.self).prefix(n).map { Float($0) }
            }
        case .bool:
            return data.map { $0 != 0 ? 1 : 0 }
        default:
            throw NSError(domain: "TfModelHandler", code: 6, userInfo: [NSLocalizedDescriptionKey: "Output dtype não suportado: \(tensor.dataType)"])
        }
    }

    /// Nested lists with `Float32List` leaves (same wire shape as Android's float[]); a
    /// leaf of NSNumbers would cost one object per value.
    static func reshape(flat: [Float], shape: [Int], offset: Int) -> Any {
        if shape.isEmpty {
            return FlutterStandardTypedData(float32: Self.floatData(flat, offset: offset, count: min(1, flat.count)))
        }
        if shape.count == 1 {
            let count = max(0, min(shape[0], flat.count - offset))
            return FlutterStandardTypedData(float32: Self.floatData(flat, offset: offset, count: count))
        }
        let step = shape.dropFirst().reduce(1, *)
        let sub = Array(shape.dropFirst())
        var list: [Any] = []
        list.reserveCapacity(shape[0])
        for i in 0..<shape[0] {
            list.append(reshape(flat: flat, shape: sub, offset: offset + i * step))
        }
        return list
    }

    private static func floatData(_ flat: [Float], offset: Int, count: Int) -> Data {
        guard count > 0 else { return Data() }
        return flat.withUnsafeBufferPointer { buf in
            Data(bytes: buf.baseAddress! + offset, count: count * MemoryLayout<Float>.size)
        }
    }

    private func resolveAssetPath(mappedKey: String) -> String? {
        if FileManager.default.fileExists(atPath: mappedKey) {
            return mappedKey
        }
        let bundle = Bundle.main
        if let p = bundle.path(forResource: mappedKey, ofType: nil) { return p }
        let name = (mappedKey as NSString).deletingPathExtension
        let ext = (mappedKey as NSString).pathExtension
        if !ext.isEmpty, let p = bundle.path(forResource: name, ofType: ext) { return p }
        return nil
    }
}

private final class ReplyBox {
    private let lock = NSLock()
    private var value: [[String: Any]] = []

    func set(_ v: [[String: Any]]) {
        lock.lock()
        value = v
        lock.unlock()
    }

    func get() -> [[String: Any]] {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

// MARK: - Loaded model

private final class LoadedModel {
    let key: String
    let interpreter: Interpreter
    let inputMeta: TensorMeta
    let outputMetas: [TensorMeta]

    init(key: String, interpreter: Interpreter, inputMeta: TensorMeta, outputMetas: [TensorMeta]) {
        self.key = key
        self.interpreter = interpreter
        self.inputMeta = inputMeta
        self.outputMetas = outputMetas
    }
}

struct TensorMeta {
    let elementKind: TfInputElementKind
    let scale: Float
    let invScale: Float
    let zeroPoint: Int
    let byteCount: Int

    init(tensor: Tensor) {
        byteCount = tensor.data.count
        // Tensor.DataType (TensorFlowLite Swift) não inclui int8 — só uInt8, int16, float32, etc.
        switch tensor.dataType {
        case .float32:
            elementKind = .float32
        case .uInt8:
            elementKind = .uint8
        default:
            elementKind = .float32
        }
        if let q = tensor.quantizationParameters {
            scale = q.scale
            invScale = q.scale > 0 ? 1 / q.scale : 1
            zeroPoint = q.zeroPoint
        } else {
            scale = 1
            invScale = 1
            zeroPoint = 0
        }
    }
}

// MARK: - Inference result

/// Espelha `InferenceResult` no Android: pins the inference frame and owns its derived crops.
private final class InferenceResult {
    static let scaledCroppedKey = "scaled-cropped"
    static let croppedKey = "cropped"

    let id: String
    let modelKey: String
    let mainFrameId: String
    let output: [String: Any]
    let lock = NSLock()
    var derivedFrameIds: [String: String] = [:]
    var closed = false

    init(modelKey: String, mainFrameId: String, output: [String: Any]) {
        self.id = UUID().uuidString
        self.modelKey = modelKey
        self.mainFrameId = mainFrameId
        self.output = output
    }

    func toFlutterMap() -> [String: Any] {
        [
            "id": id,
            "output": output,
        ]
    }
}
