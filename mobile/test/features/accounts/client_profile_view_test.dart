import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/theme/client/theme_mode.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

Session _session({bool family = false, bool trainer = false}) => Session(
  accountId: 'a',
  displayName: 'Monika Dąbrowska',
  roles: [AppRole.client, if (trainer) AppRole.trainer],
  activeRole: AppRole.client,
  staffProfileId: trainer ? 'staff' : null,
  profiles: [
    const Profile(id: 'self', displayName: 'Monika Dąbrowska', isChild: false),
    if (family)
      const Profile(id: 'kid', displayName: 'Zosia Dąbrowska', isChild: true),
  ],
  activeProfileId: 'self',
);

class _Auth extends AuthController {
  _Auth(this.session);

  final Session session;
  var loggedOut = false;

  @override
  AuthState build() => Authenticated(session);

  @override
  Future<void> logout() async {
    loggedOut = true;
    state = const Unauthenticated();
  }
}

Future<(ProviderContainer, _Auth)> _pump(
  WidgetTester tester,
  Session session,
) async {
  late _Auth auth;
  final container = ProviderContainer(
    overrides: [
      authControllerProvider.overrideWith(() => auth = _Auth(session)),
      themeModeStoreProvider.overrideWithValue(
        InMemoryThemeModeStore(ThemeMode.light),
      ),
      unreadCountProvider.overrideWith(
        (ref, scope) async => UnreadNotifications.none,
      ),
    ],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: Consumer(
        builder: (context, ref, _) => MaterialApp(
          theme: buildClientTheme(Brightness.light),
          darkTheme: buildClientTheme(Brightness.dark),
          themeMode: ref.watch(themeModeProvider),
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const ProfileScreen(),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return (container, auth);
}

Finder _switch(String label) => find.byWidgetPredicate(
  (widget) => widget is AppSwitch && widget.semanticLabel == label,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets('shows the person, their rows and one way out', (tester) async {
    await _pump(tester, _session());
    expect(find.text('Profil'), findsOneWidget);
    expect(find.text('Klient'), findsOneWidget);
    expect(find.text('Monika Dąbrowska'), findsOneWidget);
    expect(find.text('MD'), findsOneWidget);
    expect(find.text('Dane osobowe'), findsOneWidget);
    expect(find.text('Dokumenty i zgody'), findsOneWidget);
    expect(find.text('Motyw'), findsOneWidget);
    expect(find.text('Wyloguj się'), findsOneWidget);
    // One role and no family: nothing to switch, no family setting.
    expect(find.text('Zmień profil'), findsNothing);
    expect(find.text('Pokaż powiązane profile'), findsNothing);
  });

  testWidgets('a family or a second role adds the switch and the setting', (
    tester,
  ) async {
    await _pump(tester, _session(family: true));
    expect(find.text('Zmień profil'), findsOneWidget);
    expect(find.text('Pokaż powiązane profile'), findsOneWidget);
  });

  testWidgets('the theme switch turns the app dark and back', (tester) async {
    final (container, _) = await _pump(tester, _session());
    final toggle = _switch('Ciemny motyw');
    expect(tester.widget<AppSwitch>(toggle).value, isFalse);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(container.read(themeModeProvider), ThemeMode.dark);
    expect(tester.widget<AppSwitch>(toggle).value, isTrue);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(container.read(themeModeProvider), ThemeMode.light);
  });

  testWidgets('logging out asks first and can be cancelled', (tester) async {
    final (_, auth) = await _pump(tester, _session());
    await tester.tap(find.text('Wyloguj się'));
    await tester.pumpAndSettle();
    expect(find.text('Czy na pewno chcesz się wylogować?'), findsOneWidget);
    await tester.tap(find.text('Anuluj'));
    await tester.pumpAndSettle();
    expect(auth.loggedOut, isFalse);
    expect(find.text('Czy na pewno chcesz się wylogować?'), findsNothing);

    await tester.tap(find.text('Wyloguj się'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Wyloguj się').last);
    await tester.pumpAndSettle();
    expect(auth.loggedOut, isTrue);
  });

  testWidgets('"Zmień profil" opens the role picker', (tester) async {
    final (container, _) = await _pump(tester, _session(trainer: true));
    await tester.tap(find.text('Zmień profil'));
    await tester.pump();
    final state = container.read(authControllerProvider);
    expect(state, isA<Authenticated>());
    expect((state as Authenticated).choosingRole, isTrue);
  });
}
