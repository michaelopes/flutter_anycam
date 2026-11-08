import 'package:flutter/material.dart';
import 'dart:math' as math;

class FlutterAnycamRotation extends StatelessWidget {
  const FlutterAnycamRotation({
    super.key,
    required this.degress,
    required this.child,
  });

  final int degress;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Transform.rotate(
      angle: degress * (math.pi / 180),
      child: child,
    );
  }
}
