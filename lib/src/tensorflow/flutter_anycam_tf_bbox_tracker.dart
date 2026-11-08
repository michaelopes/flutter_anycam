import 'dart:math' as math;

// ---------------------------------------------------------------------------
// UUID v4 sem dependências externas
// ---------------------------------------------------------------------------

final math.Random _random = _createRandom();

math.Random _createRandom() {
  try {
    return math.Random.secure();
  } catch (_) {
    return math.Random();
  }
}

/// One shared generator: per-call seeding by timestamp gave identical ids to
/// tracks created in the same microsecond.
String _uuid() {
  String h(int bits, int len) {
    var v = 0;
    for (var i = 0; i < bits; i += 16) {
      final chunk = bits - i >= 16 ? 16 : bits - i;
      v = (v << chunk) | _random.nextInt(1 << chunk);
    }
    return v.toRadixString(16).padLeft(len, '0');
  }

  return '${h(32, 8)}-${h(16, 4)}-4${h(12, 3)}-'
      '${(8 + _random.nextInt(4)).toRadixString(16)}${h(12, 3)}-${h(48, 12)}';
}

// ---------------------------------------------------------------------------
// Estratégias de matching
// ---------------------------------------------------------------------------
typedef MatchFn = double Function(
    FlutterAnycamTfBoundingBox existing, FlutterAnycamTfBoundingBox incoming);

abstract final class MatchStrategy {
  static MatchFn get iou => (e, i) => e.iou(i);
  static MatchFn get center => (e, i) => e.centerScore(i);
  static MatchFn weighted({double iouW = 0.7, double centerW = 0.3}) =>
      (e, i) => e.iou(i) * iouW + e.centerScore(i) * centerW;
}

abstract class FlutterAnycamTfBoundingBox {
  double get x;
  double get y;
  double get width;
  double get height;

  double get right => x + width;
  double get bottom => y + height;
  double get centerX => x + width / 2;
  double get centerY => y + height / 2;
  double get area => width * height;

  FlutterAnycamTfBoundingBox({String? trackingId}) : _trackingId = trackingId;

  String? _trackingId;
  String? get trackingId => _trackingId;

  double iou(FlutterAnycamTfBoundingBox other) {
    final ix1 = x > other.x ? x : other.x;
    final iy1 = y > other.y ? y : other.y;
    final ix2 = right < other.right ? right : other.right;
    final iy2 = bottom < other.bottom ? bottom : other.bottom;
    final iw = ix2 - ix1;
    final ih = iy2 - iy1;
    if (iw <= 0 || ih <= 0) return 0.0;
    final inter = iw * ih;
    final union = area + other.area - inter;
    return union <= 0 ? 0.0 : inter / union;
  }

  double centerScore(FlutterAnycamTfBoundingBox other) {
    final dx = centerX - other.centerX;
    final dy = centerY - other.centerY;
    final dist = _sqrt(dx * dx + dy * dy);
    final avg = (width + height + other.width + other.height) / 4;
    if (avg == 0) return 0;
    final n = dist / (avg * 2);
    return n >= 1.0 ? 0.0 : 1.0 - n;
  }

  static double _sqrt(double v) {
    if (v <= 0) return 0;
    double r = v, last;
    do {
      last = r;
      r = (r + v / r) / 2;
    } while ((r - last).abs() > 1e-7);
    return r;
  }

  @override
  String toString() =>
      'BoundingBox(x: ${x.toStringAsFixed(2)}, y: ${y.toStringAsFixed(2)}, '
      'w: ${width.toStringAsFixed(2)}, h: ${height.toStringAsFixed(2)})';
}

// ---------------------------------------------------------------------------
// Track interno (mantém última box como referência de posição)
// ---------------------------------------------------------------------------
enum TrackState { tentative, confirmed, lost }

/*class _Track {
  final String id;
  FlutterAnycamTfBoundingBox ref; // última box conhecida, usada para matching
  TrackState state;
  int hits;
  int missed;
  final int firstFrame;
/*
  static const _minHitsDefault = 3;
  static const _maxMissedDefault = 5;*/

  _Track({
    required this.id,
    required FlutterAnycamTfBoundingBox box,
    required this.firstFrame,
  })  : ref = box,
        state = TrackState.tentative,
        hits = 1,
        missed = 0;

  void attach(FlutterAnycamTfBoundingBox box, int frame) {
    ref = box;
    hits++;
    missed = 0;
    _stamp(box);
  }

  void markMissed() {
    missed++;
    hits = 0;
    if (state != TrackState.tentative) state = TrackState.lost;
  }

  void _stamp(FlutterAnycamTfBoundingBox box) {
    box._trackingId = id;
  }

  void promoteIfReady(int minHits) {
    if (hits >= minHits) state = TrackState.confirmed;
  }
}*/

class _Track {
  final String id;
  FlutterAnycamTfBoundingBox ref;
  TrackState state;
  int hits;
  int missed;
  final int firstFrame;

  _Track({
    required this.id,
    required FlutterAnycamTfBoundingBox box,
    required this.firstFrame,
  })  : ref = box,
        state = TrackState.tentative,
        hits = 1,
        missed = 0;

  void attach(FlutterAnycamTfBoundingBox box, int frame) {
    ref = _smooth(ref, box);
    hits++;
    missed = 0;
    _stamp(box);
  }

  void markMissed() {
    missed++;
    if (state != TrackState.tentative) {
      state = TrackState.lost;
    }
  }

  void _stamp(FlutterAnycamTfBoundingBox box) {
    box._trackingId = id;
  }

  void promoteIfReady(int minHits) {
    if (hits >= minHits) {
      state = TrackState.confirmed;
    }
  }

  // -----------------------------------------------------------------------
  // SUAVIZAÇÃO (ESSENCIAL)
  // -----------------------------------------------------------------------
  FlutterAnycamTfBoundingBox _smooth(
    FlutterAnycamTfBoundingBox a,
    FlutterAnycamTfBoundingBox b,
  ) {
    const alpha = 0.8;

    return _LerpBox(
      x: a.x * alpha + b.x * (1 - alpha),
      y: a.y * alpha + b.y * (1 - alpha),
      width: a.width * alpha + b.width * (1 - alpha),
      height: a.height * alpha + b.height * (1 - alpha),
    );
  }
}

class _LerpBox extends FlutterAnycamTfBoundingBox {
  @override
  final double x;
  @override
  final double y;
  @override
  final double width;
  @override
  final double height;

  _LerpBox({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
  });
}

// ---------------------------------------------------------------------------
// BBoxTracker
// ---------------------------------------------------------------------------

/// Tracker de bounding boxes: [update] é void e popula [BoundingBox.trackId]
/// (e [BoundingBox.trackState], [BoundingBox.trackHits]) diretamente em cada
/// box da lista passada.
///
/// ```dart
/// final tracker = BBoxTracker();
///
/// // A cada frame:
/// tracker.update(boxes);
/// for (final box in boxes) {
///   print('${box.trackId}  →  $box');
/// }
/// ```
/*class FlutterAnycamTfBBoxTracker {
  final double threshold;
  final int maxMissed;
  final int minHits;
  final MatchFn matchFn;

  final Map<String, _Track> _tracks = {};
  int _frame = 0;

  FlutterAnycamTfBBoxTracker({
    this.threshold = 0.3,
    this.maxMissed = 5,
    this.minHits = 3,
    MatchFn? matchFn,
  }) : matchFn = matchFn ?? MatchStrategy.iou;

  // -------------------------------------------------------------------------
  // API principal
  // -------------------------------------------------------------------------

  /// Associa cada box em [boxes] a um track existente ou cria um novo.
  /// Após a chamada, cada box terá seu [BoundingBox.trackId] preenchido.
  ///
  /// [frameNumber] é opcional; se omitido usa contador interno.
  void update(List<FlutterAnycamTfBoundingBox> boxes, {int? frameNumber}) {
    _frame = frameNumber ?? _frame + 1;

    final unmatched = List<int>.generate(boxes.length, (i) => i);
    final matched = <String, int>{}; // trackId → índice em boxes

    // 1. Matching: melhor score acima do limiar
    for (final track in _tracks.values) {
      int bestIdx = -1;
      double bestScore = threshold;

      for (final i in unmatched) {
        final score = matchFn(track.ref, boxes[i]);
        if (score > bestScore) {
          bestScore = score;
          bestIdx = i;
        }
      }

      if (bestIdx >= 0) {
        matched[track.id] = bestIdx;
        unmatched.remove(bestIdx);
      }
    }

    // 2. Atualiza tracks com match e stampa as boxes
    for (final e in matched.entries) {
      final track = _tracks[e.key]!;
      track.attach(boxes[e.value], _frame);
      track.promoteIfReady(minHits);
      track._stamp(boxes[e.value]); // garante state atualizado
    }

    // 3. Missed nos sem match + limpeza em uma passagem só
    for (final track in _tracks.values) {
      if (!matched.containsKey(track.id)) {
        track.markMissed();
      }
    }
    _tracks.removeWhere((_, t) => t.missed > maxMissed);

    // 4. Cria novos tracks para boxes sem par e as stampa
    for (final i in unmatched) {
      final id = _uuid();
      final track = _Track(id: id, box: boxes[i], firstFrame: _frame);
      _tracks[id] = track;
      track._stamp(boxes[i]);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /// Número de tracks ativos.
  int get count => _tracks.length;

  /// Frame atual.
  int get currentFrame => _frame;

  /// Reseta (útil ao trocar de câmera/vídeo).
  void reset() {
    _tracks.clear();
    _frame = 0;
  }
}*/

class FlutterAnycamTfBBoxTracker {
  final double threshold;
  final int maxMissed;
  final int minHits;
  final MatchFn matchFn;

  final Map<String, _Track> _tracks = {};
  int _frame = 0;

  FlutterAnycamTfBBoxTracker({
    this.threshold = 0.1,
    this.maxMissed = 5,
    this.minHits = 3,
    MatchFn? matchFn,
  }) : matchFn = matchFn ?? MatchStrategy.weighted();

  // -------------------------------------------------------------------------
  // API principal
  // -------------------------------------------------------------------------

  void update(List<FlutterAnycamTfBoundingBox> boxes, {int? frameNumber}) {
    _frame = frameNumber ?? _frame + 1;

    final matched = <String, int>{}; // trackId → box index

    // ---------------------------------------------------------------------
    // 1. Matching: global greedy on the best (track, box) pairs first, so an
    //    early box cannot steal a track that a later box matches better.
    // ---------------------------------------------------------------------
    final pairs = <({String trackId, int box, double score})>[];
    for (final track in _tracks.values) {
      for (int i = 0; i < boxes.length; i++) {
        final score = matchFn(track.ref, boxes[i]);
        if (score > threshold) {
          pairs.add((trackId: track.id, box: i, score: score));
        }
      }
    }
    pairs.sort((a, b) => b.score.compareTo(a.score));

    final usedBoxes = <int>{};
    for (final pair in pairs) {
      if (matched.containsKey(pair.trackId) || usedBoxes.contains(pair.box)) {
        continue;
      }
      matched[pair.trackId] = pair.box;
      usedBoxes.add(pair.box);
    }
    final unmatched = <int>[
      for (int i = 0; i < boxes.length; i++)
        if (!usedBoxes.contains(i)) i,
    ];

    // ---------------------------------------------------------------------
    // 2. Atualiza tracks com match
    // ---------------------------------------------------------------------
    for (final entry in matched.entries) {
      final track = _tracks[entry.key]!;
      final box = boxes[entry.value];

      track.attach(box, _frame);
      track.promoteIfReady(minHits);
    }

    // ---------------------------------------------------------------------
    // 3. Marca missed
    // ---------------------------------------------------------------------
    for (final track in _tracks.values) {
      if (!matched.containsKey(track.id)) {
        track.markMissed();
      }
    }

    // Remove tracks mortos
    _tracks.removeWhere((_, t) => t.missed > maxMissed);

    // ---------------------------------------------------------------------
    // 4. Cria novos tracks
    // ---------------------------------------------------------------------
    for (final i in unmatched) {
      final id = _uuid();
      final track = _Track(id: id, box: boxes[i], firstFrame: _frame);
      _tracks[id] = track;
      track._stamp(boxes[i]);
    }
  }

  int get count => _tracks.length;
  int get currentFrame => _frame;

  void reset() {
    _tracks.clear();
    _frame = 0;
  }
}
