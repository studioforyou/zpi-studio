import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/app/intro/login_intro.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/choice_store.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/widgets/choice_tile.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_header.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';

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

    expect(find.text('Wybierz profil:'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);

    await tester.tap(find.text('Jan'));
    await tester.pumpAndSettle();

    expectClientShell();
  });

  testWidgets('a single-choice account skips the role picker', (tester) async {
    await pumpApp(tester, _ClientOnlyAuthRepository());

    await logIn(tester);

    expect(find.text('Wybierz profil:'), findsNothing);
    expectClientShell();
  });

  Future<void> openProfileAsAnna(WidgetTester tester) async {
    await pumpApp(tester, FakeAuthRepository());
    await logIn(tester);
    await tester.tap(find.text('Anna').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Profil'));
    await tester.pumpAndSettle();
  }

  ChoiceTile headerTile(WidgetTester tester) => tester.widget<ChoiceTile>(
    find.descendant(
      of: find.byType(ProfileHeader),
      matching: find.byType(ChoiceTile),
    ),
  );

  testWidgets('the profile header shows the active profile and its actions', (
    tester,
  ) async {
    await openProfileAsAnna(tester);

    final tile = headerTile(tester);
    expect(tile.labels, (name: 'Anna', role: 'Klient'));
    expect(tile.size, ProfileHeaderStyle.tileSize);
    expect(tile.onTap, isNull);
    expect(find.text('ANNA KOWALSKA'), findsOneWidget);
    expect(find.text('ZMIEŃ PROFIL'), findsOneWidget);
    expect(find.text('WYLOGUJ SIĘ'), findsOneWidget);

    // The text block is centred on the square, or starts at its top when it
    // is taller than the square.
    final squareTop = tester.getTopLeft(find.byType(ChoiceTile)).dy;
    final textTop = tester.getTopLeft(find.text('ANNA KOWALSKA')).dy;
    final textBottom = tester.getBottomLeft(find.text('WYLOGUJ SIĘ')).dy;
    if (textBottom - textTop <= ProfileHeaderStyle.tileSize) {
      expect(
        (textTop + textBottom) / 2,
        moreOrLessEquals(
          squareTop + ProfileHeaderStyle.tileSize / 2,
          epsilon: 1,
        ),
      );
    } else {
      expect(textTop, moreOrLessEquals(squareTop, epsilon: 1));
    }
  });

  testWidgets('changing profile returns to the profile tab behind the intro', (
    tester,
  ) async {
    await openProfileAsAnna(tester);

    await tester.tap(find.text('ZMIEŃ PROFIL'));
    await tester.pumpAndSettle();
    expect(find.text('Wybierz profil:'), findsOneWidget);

    await tester.ensureVisible(find.text('Trener'));
    await tester.tap(find.text('Trener'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(
      find.byWidgetPredicate(
        (w) => w is CustomPaint && w.painter is LoginIntroPainter,
      ),
      findsOneWidget,
    );
    await tester.pumpAndSettle();

    expectTrainerShell();
    final navigationBar = tester.widget<NavigationBar>(
      find.byType(NavigationBar),
    );
    expect(
      navigationBar.destinations[navigationBar.selectedIndex],
      isA<NavigationDestination>().having((d) => d.label, 'label', 'Profil'),
    );
    expect(headerTile(tester).labels.role, 'Trener');
  });

  final intro = find.byWidgetPredicate(
    (w) => w is CustomPaint && w.painter is LoginIntroPainter,
  );

  Future<void> expectBackOnProfileAsAnnaWithoutIntro(
    WidgetTester tester,
  ) async {
    await tester.pump();
    expect(intro, findsNothing);
    await tester.pumpAndSettle();
    expect(find.text('Wybierz profil:'), findsNothing);
    expect(headerTile(tester).labels, (name: 'Anna', role: 'Klient'));
  }

  testWidgets('back from the picker after login returns to the login screen', (
    tester,
  ) async {
    await pumpApp(tester, FakeAuthRepository());
    await logIn(tester);

    await tester.tap(find.text('POWRÓT DO LOGOWANIA'));
    await tester.pumpAndSettle();

    expect(find.text('Zaloguj się'), findsOneWidget);
  });

  testWidgets('back to profile closes the picker without changes or intro', (
    tester,
  ) async {
    await openProfileAsAnna(tester);
    await tester.tap(find.text('ZMIEŃ PROFIL'));
    await tester.pumpAndSettle();
    expect(find.text('POWRÓT DO LOGOWANIA'), findsNothing);

    await tester.tap(find.text('POWRÓT DO PROFILU'));

    await expectBackOnProfileAsAnnaWithoutIntro(tester);
  });

  testWidgets('the system back on the picker returns to the profile', (
    tester,
  ) async {
    await openProfileAsAnna(tester);
    await tester.tap(find.text('ZMIEŃ PROFIL'));
    await tester.pumpAndSettle();

    await tester.binding.handlePopRoute();

    await expectBackOnProfileAsAnnaWithoutIntro(tester);
  });

  testWidgets('picking the active profile again skips the intro', (
    tester,
  ) async {
    await openProfileAsAnna(tester);
    await tester.tap(find.text('ZMIEŃ PROFIL'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Anna').first);

    await expectBackOnProfileAsAnnaWithoutIntro(tester);
  });

  testWidgets('logout in the profile header signs out', (tester) async {
    await openProfileAsAnna(tester);

    await tester.tap(find.text('WYLOGUJ SIĘ'));
    await tester.pumpAndSettle();

    expect(find.text('Zaloguj się'), findsOneWidget);
  });

  testWidgets('a saved session reopens the profile it was left in', (
    tester,
  ) async {
    final store = InMemoryChoiceStore()..values['demo-user'] = 'TRAINER';
    await pumpApp(tester, _SignedInAuthRepository(), store: store);

    expect(find.text('Wybierz profil:'), findsNothing);
    expectTrainerShell();
  });

  testWidgets('a saved session opens the role picker without login', (
    tester,
  ) async {
    await pumpApp(tester, _SignedInAuthRepository());

    expect(find.text('Zaloguj się'), findsNothing);
    expect(find.text('Wybierz profil:'), findsOneWidget);
  });
}
