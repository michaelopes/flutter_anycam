import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/scenario_scaffold.dart';

class RtspPage extends StatefulWidget {
  const RtspPage({
    super.key,
    required this.checklist,
  });

  final List<String> checklist;

  @override
  State<RtspPage> createState() => _RtspPageState();
}

class _RtspPageState extends State<RtspPage> {
  final _url = TextEditingController(
    text: 'rtsp://192.168.1.16:554/mode=real&idc=1&ids=1',
  );
  final _user = TextEditingController(text: 'admin');
  final _pass = TextEditingController(text: '1');
  FlutterAnycamCameraSelector? _camera;
  int _frames = 0;
  String _status = 'enter credentials and Connect';

  @override
  void dispose() {
    _url.dispose();
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  void _connect() {
    setState(() {
      _camera = FlutterAnycamCameraSelector.rtsp(
        url: _url.text.trim(),
        username: _user.text.trim(),
        password: _pass.text,
      );
      _frames = 0;
      _status = 'connecting';
    });
  }

  void _disconnect() {
    setState(() {
      _camera = null;
      _status = 'disconnected';
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'RTSP',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'frames=$_frames',
        'url=${_url.text}',
      ],
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              children: [
                TextField(
                  controller: _url,
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
                        controller: _user,
                        decoration: const InputDecoration(
                          labelText: 'User',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: _pass,
                        obscureText: true,
                        decoration: const InputDecoration(
                          labelText: 'Password',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton(
                        onPressed: _connect,
                        child: const Text('Connect'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _disconnect,
                        child: const Text('Disconnect'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: _camera == null
                ? const ColoredBox(
                    color: Colors.black,
                    child: Center(
                      child: Text(
                        'No active RTSP session',
                        style: TextStyle(color: Colors.white70),
                      ),
                    ),
                  )
                : FlutterAnycamWidget(
                    key: ValueKey(_camera!.id),
                    camera: _camera!,
                    fps: 5,
                    onFrame: (_) {
                      setState(() {
                        _frames++;
                        _status = 'receiving frames';
                      });
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
