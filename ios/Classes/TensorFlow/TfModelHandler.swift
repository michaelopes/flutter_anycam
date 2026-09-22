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

    private var channel: FlutterMethodChannel?
    private var assetLookup: ((String) -> String)?

    private let inferQueue = DispatchQueue(label: "br.dev.michaellopes.flutter_anycam.tf.infer", qos: .userInitiated)
    private var models: [String: LoadedModel] = [:]
    private let modelsLock = NSLock()

    private var results: [InferenceResult] = []
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
        
        if(delegate == "xxnnpack") {
            options.isXNNPackEnabled = true;
        }

    
        let interpreter = try Interpreter(modelPath: path, options: options)
        try interpreter.allocateTensors()

        let inTensor = try interpreter.input(at: 0)
        let inMeta = TensorMeta(tensor: inTensor)

        var outBuffers: [Data] = []
        var outMetas: [TensorMeta] = []
        let nOut = interpreter.outputTensorCount
        for i in 0..<nOut {
            let t = try interpreter.output(at: i)
            outMetas.append(TensorMeta(tensor: t))
            outBuffers.append(Data(count: t.data.count))
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
        let empty = models.isEmpty
        modelsLock.unlock()

        if empty {
            resultsLock.lock()
            results.removeAll()
            resultsLock.unlock()
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
        guard let loaded = models[modelKey] else {
            modelsLock.unlock()
            throw NSError(domain: "TfModelHandler", code: 3, userInfo: [NSLocalizedDescriptionKey: "Modelo não carregado"])
        }
        modelsLock.unlock()

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

        let inputKind = loaded.inputMeta.elementKind
        let normalized = try TfFrameNormalizer.shared.normalize(
            bgra: prepared.bgra,
            width: prepared.width,
            height: prepared.height,
            normalize: normalize,
            inputKind: inputKind,
            invScale: loaded.inputMeta.invScale,
            zeroPoint: loaded.inputMeta.zeroPoint
        )

        if normalized.count != loaded.inputMeta.byteCount {
            TfFrameHandler.shared.closeFrame(prepared.frameId)
            throw NSError(domain: "TfModelHandler", code: 5, userInfo: [NSLocalizedDescriptionKey: "Tamanho do input (\(normalized.count)) ≠ esperado (\(loaded.inputMeta.byteCount))"])
        }

        let interpreter = loaded.interpreter
        try interpreter.copy(normalized, toInputAt: 0)
        try interpreter.invoke()

        var outs: [Int: Any] = [:]
        for i in 0..<interpreter.outputTensorCount {
            let tensor = try interpreter.output(at: i)
            let shape = tensor.shape.dimensions
            let flat = try readFloatOutput(tensor: tensor, meta: loaded.outputMetas[i])
            outs[i] = reshape(flat: flat, shape: shape, offset: 0)
        }

        let processed = invokeProcessTfOutput(modelKey: modelKey, outputs: outs)
        if processed.isEmpty {
            TfFrameHandler.shared.closeFrame(prepared.frameId)
        }

        var response: [[String: Any]] = []
        resultsLock.lock()
        for item in processed {
            let inf = InferenceResult(
                modelKey: modelKey,
                frameIds: [InferenceResult.mainKey: prepared.frameId],
                output: item
            )
            results.append(inf)
            response.append(inf.toFlutterMap())
        }
        resultsLock.unlock()

        return response
    }

    private func inferenceResult(byId inferenceId: String) -> InferenceResult? {
        resultsLock.lock()
        let r = results.first { $0.id == inferenceId }
        resultsLock.unlock()
        return r
    }

    func mainFrameId(for inferenceId: String) -> String? {
        inferenceResult(byId: inferenceId)?.frameIds[InferenceResult.mainKey]
    }

    func boxMap(for inferenceId: String) -> [String: Any]? {
        guard let o = inferenceResult(byId: inferenceId)?.output else { return nil }
        return o["box"] as? [String: Any]
    }

    /// Espelha `getScaledCroppedFrame` (Android) + `res.addScaledCroppedFrame` no primeiro pedido.
    func getScaledCroppedFrameMap(inferenceId: String) -> [String: Any]? {
        guard let res = inferenceResult(byId: inferenceId) else { return nil }
        guard (res.output["box"] as? [String: Any]) != nil else { return nil }

        if let eid = res.frameIds[InferenceResult.scaledCroppedKey],
           let f = TfFrameHandler.shared.getFrameById(eid) {
            return TfFrameHandler.shared.toFrameMap(f)
        }
        guard let mainId = res.frameIds[InferenceResult.mainKey],
              let box = res.output["box"] as? [String: Any],
              let f = TfFrameHandler.shared.newFrameCroppedById(frameId: mainId, box: box, enableScale: true) else {
            return nil
        }
        res.frameIds[InferenceResult.scaledCroppedKey] = f.id
        return TfFrameHandler.shared.toFrameMap(f)
    }

    /// Espelha `getCroppedFrame` (Android) + `addCroppedFrame`.
    func getCroppedFrameMap(inferenceId: String) -> [String: Any]? {
        guard let res = inferenceResult(byId: inferenceId) else { return nil }
        guard (res.output["box"] as? [String: Any]) != nil else { return nil }

        if let eid = res.frameIds[InferenceResult.croppedKey],
           let f = TfFrameHandler.shared.getFrameById(eid) {
            return TfFrameHandler.shared.toFrameMap(f)
        }
        guard let mainId = res.frameIds[InferenceResult.mainKey],
              let box = res.output["box"] as? [String: Any],
              let f = TfFrameHandler.shared.newFrameCroppedById(frameId: mainId, box: box, enableScale: false) else {
            return nil
        }
        res.frameIds[InferenceResult.croppedKey] = f.id
        return TfFrameHandler.shared.toFrameMap(f)
    }

    /// Espelha `InferenceResult.close()` (Android): fecha todos os `frameIds` (scaled-cropped, cropped, main) com ordem filhos → main.
    func closeInferenceResult(id: String) {
        var toClose: [String] = []
        resultsLock.lock()
        if let r = results.first(where: { $0.id == id }) {
            let v = r.frameIds
            if let s = v[InferenceResult.scaledCroppedKey] { toClose.append(s) }
            if let c = v[InferenceResult.croppedKey] { toClose.append(c) }
            if let m = v[InferenceResult.mainKey] { toClose.append(m) }
            for (_, fid) in v where !toClose.contains(fid) {
                toClose.append(fid)
            }
            results.removeAll { $0.id == id }
        }
        resultsLock.unlock()
        for fid in toClose {
            TfFrameHandler.shared.closeFrame(fid)
        }
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
            guard let mid = mainFrameId(for: id), let leaf = TfFrameHandler.shared.getFrameById(mid) else {
                return nil
            }
            if let pid = leaf.parentId, let p = TfFrameHandler.shared.getFrameById(pid) {
                if let ppid = p.parentId, let raw = TfFrameHandler.shared.getFrameById(ppid) {
                    return TfFrameHandler.shared.toFrameMap(raw)
                }
                return TfFrameHandler.shared.toFrameMap(p)
            }
            return TfFrameHandler.shared.toFrameMap(leaf)
        }
    }

    enum FrameKind {
        case scaledCropped, cropped, inference, raw
    }

    // MARK: - Dart

    private func invokeProcessTfOutput(modelKey: String, outputs: [Int: Any]) -> [[String: Any]] {
        guard let ch = channel else { return [] }
        var payload: [String: Any] = [:]
        for (k, v) in outputs {
            payload[String(k)] = v
        }
        let sem = DispatchSemaphore(value: 0)
        var decoded: [[String: Any]]?
        DispatchQueue.main.async {
            ch.invokeMethod("processTfOutput", arguments: ["modelKey": modelKey, "output": payload]) { result in
                if let list = result as? [[String: Any]] {
                    decoded = list
                } else if let list = result as? [Any] {
                    decoded = list.compactMap { $0 as? [String: Any] }
                }
                sem.signal()
            }
        }
        sem.wait()
        return decoded ?? []
    }

    // MARK: - Tensor helpers

    private func readFloatOutput(tensor: Tensor, meta: TensorMeta) throws -> [Float] {
        let data = tensor.data
        switch tensor.dataType {
        case .float32:
            let n = data.count / MemoryLayout<Float32>.size
            return data.withUnsafeBytes { raw in
                let buf = raw.bindMemory(to: Float32.self)
                return (0..<n).map { Float(buf[$0]) }
            }
        case .uInt8:
            let scale = meta.scale
            let zp = meta.zeroPoint
            if scale != 1 || zp != 0 {
                return data.map { (Float(Int($0)) - Float(zp)) * scale }
            }
            return data.map { Float($0) }
        case .int16:
            let n = data.count / MemoryLayout<Int16>.size
            return data.withUnsafeBytes { raw in
                let buf = raw.bindMemory(to: Int16.self)
                return (0..<n).map { Float(buf[$0]) }
            }
        default:
            throw NSError(domain: "TfModelHandler", code: 6, userInfo: [NSLocalizedDescriptionKey: "Output dtype não suportado"])
        }
    }

    private func reshape(flat: [Float], shape: [Int], offset: Int) -> Any {
        if shape.count == 1 {
            return Array(flat[offset..<(offset + shape[0])]).map { NSNumber(value: Double($0)) }
        }
        let step = shape.dropFirst().reduce(1, *)
        var list: [Any] = []
        let sub = Array(shape.dropFirst())
        for i in 0..<shape[0] {
            list.append(reshape(flat: flat, shape: sub, offset: offset + i * step))
        }
        return list
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

private struct TensorMeta {
    let elementKind: TfInputElementKind
    let scale: Float
    let invScale: Float
    let zeroPoint: Int
    let byteCount: Int

    init(tensor: Tensor) {
        byteCount = tensor.data.count
        // Tensor.DataType (TensorFlowLite Swift) não inclui int8 — só uInt8, int16, float32, etc.
        // kTfLiteInt8 no runtime C não é mapeado no enum Swift; entradas/saídas INT8 puras podem
        // exigir outro binding ou modelo em float/uint8. Ver: Tensor.init(type:) em Tensor.swift.
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

/// Espelha `InferenceResult` no Android: `modelItemId` + `frameIds` (main, scaled-cropped, cropped) + `output.toMap()`.
private final class InferenceResult {
    static let mainKey = "main"
    static let scaledCroppedKey = "scaled-cropped"
    static let croppedKey = "cropped"

    let id: String
    let modelKey: String
    var frameIds: [String: String]
    let output: [String: Any]

    init(modelKey: String, frameIds: [String: String], output: [String: Any]) {
        self.id = UUID().uuidString
        self.modelKey = modelKey
        self.frameIds = frameIds
        self.output = output
    }

    func toFlutterMap() -> [String: Any] {
        [
            "id": id,
            "output": output,
        ]
    }
}
