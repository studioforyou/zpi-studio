import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

class _FailingAuthRepository implements AuthRepository {
  const _FailingAuthRepository(this.reason);

  final AuthFailureReason reason;

  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async => throw AuthFailure(reason);

  @override
  Future<Session?> restore() async => null;

  @override
  Future<void> logout() async {}
}

void main() {
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

  testWidgets('a failed login stays on the login screen with a message', (
    tester,
  ) async {
    await pumpApp(
      tester,
      const _FailingAuthRepository(AuthFailureReason.invalidCredentials),
    );

    await tester.tap(find.text('Zaloguj się'));
    await tester.pumpAndSettle();

    expect(find.text('Nieprawidłowy e-mail lub hasło.'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });

  testWidgets('an account waiting for approval is told so', (tester) async {
    await pumpApp(
      tester,
      const _FailingAuthRepository(AuthFailureReason.pendingApproval),
    );

    await tester.tap(find.text('Zaloguj się'));
    await tester.pumpAndSettle();

    expect(
      find.text('Twoje konto czeka na zatwierdzenie przez studio.'),
      findsOneWidget,
    );
  });
}
