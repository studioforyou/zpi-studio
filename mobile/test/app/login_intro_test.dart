import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/app/intro/login_intro.dart';
import 'package:mobile/app/intro/login_intro_timings.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/login_screen.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

/// Starts with the demo user still signed in from an earlier run.
class _SignedInAuthRepository extends FakeAuthRepository {
  @override
  Future<Session?> restore() => login(email: '', password: '');
}

void main() {
  const timings = LoginIntroTimings();
  Duration seconds(double s) => Duration(milliseconds: (s * 1000).round());

  Future<void> pumpApp(WidgetTester tester, AuthRepository repository) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(repository),
          groupClassesProvider.overrideWith((ref, query) async => const []),
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

  final intro = find.byWidgetPredicate(
    (w) => w is CustomPaint && w.painter is LoginIntroPainter,
  );

  testWidgets('the login screen stays until the curtains have closed', (
    tester,
  ) async {
    await pumpApp(tester, FakeAuthRepository());

    await tester.tap(find.text('Zaloguj się'));
    await tester.pump();
    await tester.pump(seconds(timings.curtains / 2));

    expect(intro, findsOneWidget);
    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);

    await tester.pump(seconds(timings.curtains));
    await tester.pump();

    expect(find.byType(LoginScreen), findsNothing);
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(intro, findsOneWidget);

    await tester.pumpAndSettle();

    expect(intro, findsNothing);
  });

  testWidgets('a tap skips the intro', (tester) async {
    await pumpApp(tester, FakeAuthRepository());

    await tester.tap(find.text('Zaloguj się'));
    await tester.pump();
    await tester.pump(seconds(0.05));
    await tester.tap(intro);
    await tester.pump();
    await tester.pump();

    expect(intro, findsNothing);
    expect(find.byType(NavigationBar), findsOneWidget);
  });

  testWidgets('no intro with reduced motion', (tester) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await pumpApp(tester, FakeAuthRepository());

    await tester.tap(find.text('Zaloguj się'));
    await tester.pump();

    expect(intro, findsNothing);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsOneWidget);
  });

  testWidgets('no intro when a saved session is restored', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(_SignedInAuthRepository()),
          groupClassesProvider.overrideWith((ref, query) async => const []),
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
    await tester.pump();
    await tester.pump();

    expect(intro, findsNothing);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsOneWidget);
  });
}
