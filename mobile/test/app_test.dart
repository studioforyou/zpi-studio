import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

/// Starts with the demo user still signed in from an earlier run.
class _SignedInAuthRepository extends FakeAuthRepository {
  @override
  Future<Session?> restore() => login(email: '', password: '');
}

void main() {
  // Nothing here touches Firebase or Dio; the app uses FakeAuthRepository.
  Future<void> pumpApp(WidgetTester tester, AuthRepository repository) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
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
  }

  void expectClientShell() {
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
  }

  testWidgets('login leads to the client shell', (tester) async {
    await pumpApp(tester, FakeAuthRepository());

    expect(find.text('Zaloguj się'), findsOneWidget);

    await tester.tap(find.text('Zaloguj się'));
    await tester.pumpAndSettle();

    expectClientShell();
  });

  testWidgets('a saved session opens the client shell without login', (
    tester,
  ) async {
    await pumpApp(tester, _SignedInAuthRepository());

    expect(find.text('Zaloguj się'), findsNothing);
    expectClientShell();
  });
}
