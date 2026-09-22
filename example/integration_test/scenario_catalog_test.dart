import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:flutter_anycam_example/app.dart';
import 'package:flutter_anycam_example/scenarios/catalog.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('catalog tiles are listed and navigable', (tester) async {
    final catalog = buildScenarioCatalog();
    await tester.pumpWidget(const AnycamExampleApp(cameras: []));
    await tester.pumpAndSettle();

    expect(find.text('flutter_anycam scenarios'), findsOneWidget);

    final scrollable = find.byType(Scrollable).first;
    for (final scenario in catalog) {
      final tile = find.byKey(Key('scenario_${scenario.id}'));
      await tester.scrollUntilVisible(tile, 300, scrollable: scrollable);
      await tester.pumpAndSettle();
      expect(
        tile,
        findsOneWidget,
        reason: 'Missing tile: ${scenario.id}',
      );
    }

    final target = find.byKey(const Key('scenario_lifecycle'));
    await tester.scrollUntilVisible(target, 300, scrollable: scrollable);
    await tester.pumpAndSettle();
    await tester.tap(target);
    await tester.pumpAndSettle();

    expect(find.text('Lifecycle'), findsWidgets);
    expect(find.text('Checklist'), findsOneWidget);

    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('flutter_anycam scenarios'), findsOneWidget);
  });
}
