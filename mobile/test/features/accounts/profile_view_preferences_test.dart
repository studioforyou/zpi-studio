import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _parent = (accountId: 'parent', profileId: 'monika');
const _child = (accountId: 'parent', profileId: 'zosia');
const _session = Session(
  accountId: 'parent',
  displayName: 'Monika Dąbrowska',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'monika', displayName: 'Monika Dąbrowska', isChild: false),
    Profile(id: 'zosia', displayName: 'Zosia Dąbrowska', isChild: true),
  ],
  activeProfileId: 'monika',
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  test(
    'the preference persists per profile and is separate from other accounts',
    () async {
      final container = ProviderContainer();
      expect(
        await container.read(profileViewPreferencesProvider(_parent).future),
        isTrue,
      );
      await container
          .read(profileViewPreferencesProvider(_parent).notifier)
          .setShowRelated(false);
      expect(
        await container.read(profileViewPreferencesProvider(_child).future),
        isTrue,
      );
      expect(
        await container.read(
          profileViewPreferencesProvider((
            accountId: 'other',
            profileId: 'monika',
          )).future,
        ),
        isTrue,
      );
      container.dispose();
      final restored = ProviderContainer();
      addTearDown(restored.dispose);
      expect(
        await restored.read(profileViewPreferencesProvider(_parent).future),
        isFalse,
      );
      expect(
        await restored.read(profileViewPreferencesProvider(_child).future),
        isTrue,
      );
    },
  );

  testWidgets('the profile switch saves only the displayed person preference', (
    tester,
  ) async {
    final container = ProviderContainer(
      overrides: [authControllerProvider.overrideWith(_Auth.new)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const ProfileScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final toggle = find.byType(SwitchListTile);
    await tester.ensureVisible(toggle);
    expect(tester.widget<SwitchListTile>(toggle).value, isTrue);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(
      container.read(profileViewPreferencesProvider(_parent)).value,
      isFalse,
    );
    expect(
      await container.read(profileViewPreferencesProvider(_child).future),
      isTrue,
    );
  });
}
