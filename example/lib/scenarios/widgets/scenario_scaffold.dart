import 'package:flutter/material.dart';

import 'status_panel.dart';

class ScenarioScaffold extends StatelessWidget {
  const ScenarioScaffold({
    super.key,
    required this.title,
    required this.checklist,
    required this.body,
    this.statusLines = const [],
    this.actions = const [],
    this.bottomBar,
  });

  final String title;
  final List<String> checklist;
  final Widget body;
  final List<String> statusLines;
  final List<Widget> actions;
  final Widget? bottomBar;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: actions,
      ),
      body: Column(
        children: [
          Material(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: ExpansionTile(
              initiallyExpanded: true,
              title: const Text('Checklist'),
              children: [
                for (final item in checklist)
                  ListTile(
                    dense: true,
                    leading: const Icon(Icons.check_box_outline_blank, size: 18),
                    title: Text(item, style: const TextStyle(fontSize: 13)),
                  ),
              ],
            ),
          ),
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                body,
                if (statusLines.isNotEmpty)
                  Positioned(
                    left: 8,
                    right: 8,
                    bottom: 8,
                    child: StatusPanel(lines: statusLines),
                  ),
              ],
            ),
          ),
          if (bottomBar != null) bottomBar!,
        ],
      ),
    );
  }
}
