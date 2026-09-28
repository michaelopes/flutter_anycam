import 'package:flutter/material.dart';

class CameraLane extends StatelessWidget {
  const CameraLane({
    super.key,
    required this.title,
    required this.child,
    this.badge,
  });

  final String title;
  final Widget child;
  final Widget? badge;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        child,
        Positioned(
          left: 8,
          top: 8,
          child: Material(
            color: Colors.black.withValues(alpha: 0.65),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Text(
                title,
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
            ),
          ),
        ),
        if (badge != null)
          Positioned(
            top: 8,
            right: 8,
            child: badge!,
          ),
      ],
    );
  }
}
