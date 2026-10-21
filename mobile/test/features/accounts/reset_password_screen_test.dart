import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/reset_password_screen.dart';

class _ResetAuthRepository implements AuthRepository {
  _ResetAuthRepository({this.failure});

  final PasswordResetFailureReason? failure;
  final requests = <String>[];

  @override
  Future<Session> login({required String email, required String password}) =>
      throw UnimplementedError();

  @override
  Future<Session?> restore() async => null;

  @override
  Future<void> logout() async {}

  @override
  Future<void> sendPasswordReset(String email) async {
    final reason = failure;
    if (reason != null) {
      throw PasswordResetFailure(reason);
    }
    requests.add(email);
  }
}

void main() {
  final cooldown = passwordResetCooldown.inSeconds;

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

  Future<void> openReset(WidgetTester tester, {String email = ''}) async {
    if (email.isNotEmpty) {
      await tester.enterText(find.byType(TextField).first, email);
    }
    await tester.tap(find.text('Nie pamiętam hasła'));
    await tester.pumpAndSettle();
  }

  Finder resendButton() => find.byType(FilledButton);

  testWidgets('the login screen links to the reset screen with the e-mail', (
    tester,
  ) async {
    await pumpApp(tester, _ResetAuthRepository());

    await openReset(tester, email: 'anna@example.com');

    expect(find.text('Reset hasła'), findsOneWidget);
    expect(find.text('anna@example.com'), findsOneWidget);
  });

  testWidgets('the link is centred under the login button', (tester) async {
    await pumpApp(tester, _ResetAuthRepository());

    final button = tester.getRect(find.byType(FilledButton));
    final link = tester.getRect(find.text('Nie pamiętam hasła'));

    expect(link.top, greaterThan(button.bottom));
    expect(link.center.dx, closeTo(button.center.dx, 1));
  });

  testWidgets('sending the link confirms it and counts down to a resend', (
    tester,
  ) async {
    final repository = _ResetAuthRepository();
    await pumpApp(tester, repository);
    await openReset(tester, email: ' anna@example.com ');

    await tester.tap(find.text('Wyślij link'));
    await tester.pump();

    expect(repository.requests, ['anna@example.com']);
    expect(find.textContaining('Jeśli konto o tym adresie'), findsOneWidget);
    expect(find.text('Wyślij ponownie ($cooldown s)'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(resendButton()).onPressed,
      isNull,
      reason: 'resending is blocked during the cooldown',
    );

    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Wyślij ponownie (${cooldown - 1} s)'), findsOneWidget);

    await tester.pump(Duration(seconds: cooldown - 1));
    expect(find.text('Wyślij ponownie'), findsOneWidget);
    expect(tester.widget<FilledButton>(resendButton()).onPressed, isNotNull);

    await tester.tap(find.text('Wyślij ponownie'));
    await tester.pump();
    expect(repository.requests, hasLength(2));
    expect(find.text('Wyślij ponownie ($cooldown s)'), findsOneWidget);

    await tester.pump(Duration(seconds: cooldown));
  });

  testWidgets('the layout does not move when a message appears', (
    tester,
  ) async {
    await pumpApp(tester, _ResetAuthRepository());
    await openReset(tester, email: 'anna@example.com');
    final button = tester.getTopLeft(find.byType(FilledButton));
    final field = tester.getTopLeft(find.byType(TextField));

    await tester.tap(find.text('Wyślij link'));
    await tester.pump();

    expect(find.textContaining('Jeśli konto o tym adresie'), findsOneWidget);
    expect(tester.getTopLeft(find.byType(FilledButton)), button);
    expect(tester.getTopLeft(find.byType(TextField)), field);

    await tester.pump(Duration(seconds: cooldown));
  });

  testWidgets('the back link is centred under the resend button', (
    tester,
  ) async {
    await pumpApp(tester, _ResetAuthRepository());
    await openReset(tester, email: 'anna@example.com');

    final button = tester.getRect(find.byType(FilledButton));
    final link = tester.getRect(find.text('Wróć do logowania'));

    expect(link.top, greaterThan(button.bottom));
    expect(link.center.dx, closeTo(button.center.dx, 1));
  });

  testWidgets('an empty e-mail is rejected without a request', (tester) async {
    final repository = _ResetAuthRepository();
    await pumpApp(tester, repository);
    await openReset(tester);

    await tester.tap(find.text('Wyślij link'));
    await tester.pump();

    expect(find.text('Podaj poprawny adres e-mail.'), findsOneWidget);
    expect(repository.requests, isEmpty);
    expect(find.text('Wyślij link'), findsOneWidget);
  });

  for (final (reason, message) in [
    (PasswordResetFailureReason.invalidEmail, 'Podaj poprawny adres e-mail.'),
    (
      PasswordResetFailureReason.tooManyAttempts,
      'Zbyt wiele prób. Spróbuj ponownie za chwilę.',
    ),
    (
      PasswordResetFailureReason.network,
      'Brak połączenia z serwerem. Sprawdź internet i spróbuj ponownie.',
    ),
    (
      PasswordResetFailureReason.unknown,
      'Nie udało się wysłać wiadomości. Spróbuj ponownie.',
    ),
  ]) {
    testWidgets('a ${reason.name} failure is explained and can be retried', (
      tester,
    ) async {
      await pumpApp(tester, _ResetAuthRepository(failure: reason));
      await openReset(tester, email: 'anna@example.com');

      await tester.tap(find.text('Wyślij link'));
      await tester.pump();

      expect(find.text(message), findsOneWidget);
      expect(find.text('Wyślij link'), findsOneWidget);
      expect(tester.widget<FilledButton>(resendButton()).onPressed, isNotNull);
    });
  }

  testWidgets('going back returns to the login screen', (tester) async {
    await pumpApp(tester, _ResetAuthRepository());
    await openReset(tester);

    await tester.tap(find.text('Wróć do logowania'));
    await tester.pumpAndSettle();

    expect(find.text('Zaloguj się'), findsOneWidget);
  });

  testWidgets('the reset screen also works in the dark system theme', (
    tester,
  ) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await pumpApp(tester, _ResetAuthRepository());
    await openReset(tester, email: 'anna@example.com');

    await tester.tap(find.text('Wyślij link'));
    await tester.pump();

    expect(find.textContaining('Jeśli konto o tym adresie'), findsOneWidget);

    await tester.pump(Duration(seconds: cooldown));
  });
}
