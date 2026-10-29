import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/choice_store.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

/// Starts with the demo user still signed in from an earlier run.
class _SignedInAuthRepository extends FakeAuthRepository {
  @override
  Future<Session?> restore() => login(email: '', password: '');
}

/// Starts signed out; signs in as a client with only their own profile.
class _ClientOnlyAuthRepository extends FakeAuthRepository {
  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    final session = await super.login(email: email, password: password);
    return Session(
      accountId: session.accountId,
      displayName: session.displayName,
      roles: const [AppRole.client],
      activeRole: AppRole.client,
      staffProfileId: null,
      profiles: [session.profiles.first],
      activeProfileId: session.activeProfileId,
    );
  }
}

void main() {
  // Nothing here touches Firebase or Dio; the app uses FakeAuthRepository.
  Future<void> pumpApp(
    WidgetTester tester,
    AuthRepository repository, {
    ChoiceStore? store,
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
          choiceStoreProvider.overrideWithValue(store ?? InMemoryChoiceStore()),
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

  void expectTrainerShell() {
    final navigationBar = find.byType(NavigationBar);
    expect(
      find.descendant(of: navigationBar, matching: find.text('Treningi')),
      findsOneWidget,
    );
  }

  Future<void> logIn(WidgetTester tester) async {
    await tester.tap(find.text('Zaloguj się'));
    await tester.pumpAndSettle();
  }

  testWidgets('login opens the role picker, a pick opens its shell', (
    tester,
  ) async {
    await pumpApp(tester, FakeAuthRepository());
    expect(find.text('Zaloguj się'), findsOneWidget);

    await logIn(tester);

    expect(find.text('Kto korzysta?'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);

    await tester.tap(find.text('Jan Kowalski'));
    await tester.pumpAndSettle();

    expectClientShell();
  });

  testWidgets('a single-choice account skips the role picker', (tester) async {
    await pumpApp(tester, _ClientOnlyAuthRepository());

    await logIn(tester);

    expect(find.text('Kto korzysta?'), findsNothing);
    expectClientShell();
  });

  testWidgets('the profile tab goes back to the role picker', (tester) async {
    await pumpApp(tester, FakeAuthRepository());
    await logIn(tester);
    await tester.tap(find.text('Anna Kowalska').first);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Profil'));
    await tester.pumpAndSettle();
    expect(find.text('Klient · Anna Kowalska'), findsOneWidget);
    await tester.tap(find.text('Zmień rolę'));
    await tester.pumpAndSettle();

    expect(find.text('Kto korzysta?'), findsOneWidget);

    await tester.tap(find.text('Trener'));
    await tester.pumpAndSettle();

    expectTrainerShell();
  });

  testWidgets('a saved session reopens the profile it was left in', (
    tester,
  ) async {
    final store = InMemoryChoiceStore()..values['demo-user'] = 'TRAINER';
    await pumpApp(tester, _SignedInAuthRepository(), store: store);

    expect(find.text('Kto korzysta?'), findsNothing);
    expectTrainerShell();
  });

  testWidgets('a saved session opens the role picker without login', (
    tester,
  ) async {
    await pumpApp(tester, _SignedInAuthRepository());

    expect(find.text('Zaloguj się'), findsNothing);
    expect(find.text('Kto korzysta?'), findsOneWidget);
  });
}
