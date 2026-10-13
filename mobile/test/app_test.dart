import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';

void main() {
  testWidgets('login leads to the client shell', (tester) async {
    // Nothing here creates Dio or secure storage; login uses
    // FakeAuthRepository.
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appConfigProvider.overrideWithValue(
            const AppConfig(
              flavor: AppFlavor.dev,
              apiBaseUrl: 'http://localhost',
            ),
          ),
        ],
        child: const App(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Zaloguj się'), findsOneWidget);

    await tester.tap(find.text('Zaloguj się'));
    await tester.pumpAndSettle();

    final navigationBar = find.byType(NavigationBar);
    for (final label in [
      'Grafik',
      'Karnety',
      'Zapisy',
      'Wiadomości',
      'Profil',
    ]) {
      expect(
        find.descendant(of: navigationBar, matching: find.text(label)),
        findsOneWidget,
      );
    }
  });
}
