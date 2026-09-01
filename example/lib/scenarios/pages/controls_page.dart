import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class ControlsPage extends StatefulWidget {
  const ControlsPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<ControlsPage> createState() => _ControlsPageState();
}

class _ControlsPageState extends State<ControlsPage> {
  late FlutterAnycamCameraSelector? _selected;
  double _zoom = 1.0;
  int _exposure = 0;
  bool _flash = false;
  String _lastAction = 'none';

  @override
  void initState() {
    super.initState();
    _selected = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  Future<void> _applyZoom(double v) async {
    if (_selected == null) return;
    setState(() {
      _zoom = v;
      _lastAction = 'zoom=$v';
    });
    await FlutterAnycam.setZoom(camera: _selected!, zoom: v);
  }

  Future<void> _applyExposure(int v) async {
    if (_selected == null) return;
    setState(() {
      _exposure = v;
      _lastAction = 'exposure=$v';
    });
    await FlutterAnycam.setExposureCompensation(camera: _selected!, value: v);
  }

  Future<void> _toggleFlash() async {
    setState(() {
      _flash = !_flash;
      _lastAction = 'flash=$_flash';
    });
    if (_flash) {
      await FlutterAnycam.enableFlash();
    } else {
      await FlutterAnycam.disableFlash();
    }
  }

  @override
  Widget build(BuildContext context) {
    final deviceCams = widget.cameras
        .where((c) =>
            c.lensFacing == FlutterAnycamLensFacing.back ||
            c.lensFacing == FlutterAnycamLensFacing.front)
        .toList();

    return ScenarioScaffold(
      title: 'Zoom / exposure / flash',
      checklist: widget.checklist,
      statusLines: [
        'camera=${_selected?.id ?? "-"}',
        'zoom=$_zoom exposure=$_exposure flash=$_flash',
        'last=$_lastAction',
      ],
      body: _selected == null
          ? const Center(child: Text('No cameras'))
          : Column(
              children: [
                Expanded(
                  child: FlutterAnycamWidget(
                    camera: _selected!,
                    fps: 5,
                    frameDeliveryEnabled: false,
                  ),
                ),
                SizedBox(
                  height: 160,
                  child: ListView(
                    children: [
                      for (final c in deviceCams)
                        CameraSelectorTile(
                          camera: c,
                          selected: c.id == _selected!.id,
                          onTap: () => setState(() => _selected = c),
                        ),
                    ],
                  ),
                ),
              ],
            ),
      bottomBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  const Text('Zoom'),
                  Expanded(
                    child: Slider(
                      value: _zoom,
                      min: 1,
                      max: 8,
                      divisions: 14,
                      label: _zoom.toStringAsFixed(1),
                      onChanged: (v) => _applyZoom(v),
                    ),
                  ),
                ],
              ),
              Row(
                children: [
                  const Text('Exp'),
                  Expanded(
                    child: Slider(
                      value: _exposure.toDouble(),
                      min: (_selected?.minExposureValue ?? -4).toDouble(),
                      max: (_selected?.maxExposureValue ?? 4)
                          .toDouble()
                          .clamp(1, 12),
                      divisions: 16,
                      label: '$_exposure',
                      onChanged: (v) => _applyExposure(v.round()),
                    ),
                  ),
                  IconButton(
                    onPressed: _toggleFlash,
                    icon: Icon(_flash ? Icons.flash_on : Icons.flash_off),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
