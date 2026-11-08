import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import 'catalog.dart';
import 'scenario.dart';
import 'widgets/camera_selector_tile.dart';

class ScenarioHubPage extends StatefulWidget {
  const ScenarioHubPage({
    super.key,
    required this.cameras,
  });

  final List<FlutterAnycamCameraSelector> cameras;

  @override
  State<ScenarioHubPage> createState() => _ScenarioHubPageState();
}

class _ScenarioHubPageState extends State<ScenarioHubPage> {
  late List<FlutterAnycamCameraSelector> _cameras = widget.cameras;
  bool _refreshing = false;

  Future<void> _refreshCameras() async {
    setState(() => _refreshing = true);
    try {
      final cameras = await FlutterAnycam.availableCameras();
      if (!mounted) return;
      setState(() => _cameras = cameras);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('availableCameras failed: $e')),
      );
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scenarios = buildScenarioCatalog();
    return Scaffold(
      appBar: AppBar(
        title: const Text('flutter_anycam scenarios'),
        actions: [
          IconButton(
            tooltip: 'Refresh cameras',
            onPressed: _refreshing ? null : _refreshCameras,
            icon: _refreshing
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh),
          ),
        ],
      ),
      body: ListView(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Text(
              'Cameras (${_cameras.length})',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          if (_cameras.isEmpty)
            const ListTile(
              leading: Icon(Icons.warning_amber),
              title: Text('No cameras returned by availableCameras()'),
              subtitle: Text('Some scenarios will show empty states'),
            )
          else
            ..._cameras.map(
              (c) => CameraSelectorTile(camera: c),
            ),
          const Divider(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: Text(
              'Scenarios',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          for (final scenario in scenarios)
            _ScenarioTile(
              scenario: scenario,
              cameras: _cameras,
            ),
        ],
      ),
    );
  }
}

class _ScenarioTile extends StatelessWidget {
  const _ScenarioTile({
    required this.scenario,
    required this.cameras,
  });

  final Scenario scenario;
  final List<FlutterAnycamCameraSelector> cameras;

  @override
  Widget build(BuildContext context) {
    final available = scenario.isAvailableOnThisPlatform;
    return ListTile(
      key: Key('scenario_${scenario.id}'),
      enabled: available,
      leading: Icon(
        available ? Icons.play_circle_outline : Icons.block,
      ),
      title: Text(scenario.title),
      subtitle: Text(
        available
            ? scenario.subtitle
            : '${scenario.subtitle}\n${scenario.unavailableReason}',
      ),
      isThreeLine: !available,
      trailing: available ? const Icon(Icons.chevron_right) : null,
      onTap: available
          ? () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => scenario.builder(cameras),
                  settings: RouteSettings(name: '/scenario/${scenario.id}'),
                ),
              );
            }
          : null,
    );
  }
}
