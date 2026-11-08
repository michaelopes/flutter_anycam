import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_anycam_example/app.dart';

void main() {
  testWidgets('Scenario hub smoke with empty cameras', (tester) async {
    await tester.pumpWidget(const AnycamExampleApp(cameras: []));
    await tester.pumpAndSettle();

    expect(find.text('flutter_anycam scenarios'), findsOneWidget);
    expect(find.text('Scenarios'), findsOneWidget);
    expect(find.text('Single back preview'), findsOneWidget);
    expect(find.text('Dual camera'), findsOneWidget);
    expect(
      find.text('No cameras returned by availableCameras()'),
      findsOneWidget,
    );
  });
}
