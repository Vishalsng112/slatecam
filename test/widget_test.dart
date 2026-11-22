import 'package:flutter_test/flutter_test.dart';
import 'package:slatecam/main.dart';

void main() {
  testWidgets('Phase 0 shell renders', (tester) async {
    await tester.pumpWidget(const SlateCamApp());

    expect(find.text('SLATECAM'), findsOneWidget);
    expect(find.text('Open-source cinema camera'), findsOneWidget);
  });
}
