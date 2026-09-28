import 'package:flutter/material.dart';

import '../lab_config.dart';

class TripleLabBar extends StatelessWidget {
  const TripleLabBar({
    super.key,
    required this.url,
    required this.user,
    required this.pass,
    required this.usbLabel,
    required this.backLabel,
    required this.onRefresh,
    required this.onConnect,
    required this.onDisconnect,
    this.onLabPreset,
  });

  final TextEditingController url;
  final TextEditingController user;
  final TextEditingController pass;
  final String usbLabel;
  final String backLabel;
  final VoidCallback onRefresh;
  final VoidCallback onConnect;
  final VoidCallback onDisconnect;
  final VoidCallback? onLabPreset;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: Column(
        children: [
          Row(
            children: [
              ActionChip(
                avatar: const Icon(Icons.lan, size: 18),
                label: const Text('Lab RTSP'),
                onPressed: onLabPreset,
              ),
              const SizedBox(width: 8),
              ActionChip(
                avatar: const Icon(Icons.refresh, size: 18),
                label: const Text('Refresh cams'),
                onPressed: onRefresh,
              ),
            ],
          ),
          const SizedBox(height: 8),
          TextField(
            controller: url,
            decoration: const InputDecoration(
              labelText: 'RTSP URL',
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: user,
                  decoration: const InputDecoration(
                    labelText: 'User (optional)',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: pass,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Password (optional)',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              'USB: $usbLabel   Back: $backLabel   RTSP: ${url.text.isEmpty ? kLabRtspUrl : url.text}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: FilledButton(
                  onPressed: onConnect,
                  child: const Text('Open 3 cameras'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton(
                  onPressed: onDisconnect,
                  child: const Text('Close'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
