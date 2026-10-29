import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/role_picker_screen.dart';
import 'package:mobile/features/accounts/presentation/widgets/choice_tile.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session(int children) => Session(
  accountId: 'account',
  displayName: 'Karolina Szymańska',
  roles: const [AppRole.client, AppRole.trainer],
  activeRole: AppRole.client,
  staffProfileId: 'staff',
  profiles: [
    const Profile(
      id: 'self',
      displayName: 'Karolina Szymańska',
      isChild: false,
    ),
    for (var i = 0; i < children; i++)
      Profile(id: 'child-$i', displayName: 'Kuba$i Szymański', isChild: true),
  ],
  activeProfileId: 'self',
);

class _SignedInAuthController extends AuthController {
  _SignedInAuthController(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session, choosingRole: true);
}

void main() {
  Future<void> pumpPicker(WidgetTester tester, Session session) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(
            () => _SignedInAuthController(session),
          ),
        ],
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: RolePickerScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('shows first names only, with the role below', (tester) async {
    await pumpPicker(tester, _session(1));

    expect(find.text('Wybierz profil:'), findsOneWidget);
    expect(find.text('Karolina'), findsNWidgets(2));
    expect(find.text('Kuba0'), findsOneWidget);
    expect(find.textContaining('Szyma'), findsNothing);
    expect(find.text('Klient'), findsOneWidget);
    expect(find.text('Dziecko'), findsOneWidget);
    expect(find.text('Trener'), findsOneWidget);
  });

  testWidgets('tiles split the row width evenly', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await pumpPicker(tester, _session(1));

    final tiles = tester.widgetList<ChoiceTile>(find.byType(ChoiceTile));
    // The screen minus its side margins, but no wider than the row limit.
    const screenRow = 390 - 2 * RolePickerStyle.sideMargin;
    const rowWidth = screenRow < RolePickerStyle.maxRowWidth
        ? screenRow
        : RolePickerStyle.maxRowWidth;
    const expected =
        (rowWidth -
            RolePickerStyle.tileSpacing * (RolePickerStyle.tilesPerRow - 1)) /
        RolePickerStyle.tilesPerRow;
    expect(tiles.map((tile) => tile.size), everyElement(expected));
  });

  testWidgets('many profiles on a small screen scroll without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.reset);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await pumpPicker(tester, _session(8));

    expect(tester.takeException(), isNull);
    expect(find.byType(ChoiceTile), findsNWidgets(10));
    final titleTop = tester.getTopLeft(find.text('Wybierz profil:')).dy;

    await tester.scrollUntilVisible(
      find.text('Trener'),
      200,
      scrollable: find.byType(Scrollable),
    );
    expect(find.text('Trener').hitTestable(), findsOneWidget);
    // The title stays pinned at the top while the tiles scroll.
    expect(tester.getTopLeft(find.text('Wybierz profil:')).dy, titleTop);
    expect(find.text('Wybierz profil:').hitTestable(), findsOneWidget);
  });
}
