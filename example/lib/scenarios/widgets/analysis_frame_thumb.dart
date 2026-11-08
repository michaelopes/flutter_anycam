import 'dart:typed_data';

import 'package:flutter/material.dart';

class AnalysisFrameThumb extends StatelessWidget {
  const AnalysisFrameThumb({
    super.key,
    required this.jpeg,
    this.label = 'Analysis frame',
    this.width = 160,
    this.height = 120,
  });

  final Uint8List? jpeg;
  final String label;
  final double width;
  final double height;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.75),
      child: Padding(
        padding: const EdgeInsets.all(6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              label,
              style: const TextStyle(color: Colors.white, fontSize: 11),
            ),
            const SizedBox(height: 4),
            Container(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white, width: 2),
              ),
              width: width,
              height: height,
              child: jpeg == null
                  ? const Center(
                      child: Text(
                        'waiting…',
                        style: TextStyle(color: Colors.white70, fontSize: 11),
                      ),
                    )
                  : Image.memory(
                      jpeg!,
                      fit: BoxFit.contain,
                      gaplessPlayback: true,
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
