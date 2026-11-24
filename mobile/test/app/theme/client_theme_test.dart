import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/app/theme/client/client_theme_config.dart';
import 'package:mobile/app/theme/client/theme_mode.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';

Session _session(AppRole role) => Session(
  accountId: 'a',
  displayName: 'Anna',
  roles: const [AppRole.client, AppRole.trainer],
  activeRole: role,
  staffProfileId: null,
  profiles: const [],
  activeProfileId: null,
);

void main() {
  group('buildClientTheme', () {
    for (final brightness in Brightness.values) {
      test('uses the configured values ($brightness)', () {
        final theme = buildClientTheme(brightness);
        final ct = theme.extension<ClientTheme>()!;
        final palette = brightness == Brightness.dark
            ? clientThemeConfig.dark
            : clientThemeConfig.light;

        expect(theme.brightness, brightness);
        expect(theme.scaffoldBackgroundColor, palette.background);
        expect(theme.colorScheme.primary, palette.primary);
        expect(ct.colors.ink, palette.ink);
        expect(ct.text.pageTitle.fontSize, 27);
        expect(ct.text.pageTitle.fontFamily, 'FamiljenGrotesk');
        expect(ct.text.pageTitle.letterSpacing, closeTo(-0.81, 0.001));
        expect(theme.splashFactory, NoSplash.splashFactory);
        expect(theme.cardTheme.elevation, 0);
        expect(theme.bottomSheetTheme.modalElevation, 0);
      });
    }

    testWidgets('context.ct falls back outside the client theme', (
      tester,
    ) async {
      late ClientTheme ct;
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData.dark(),
          home: Builder(
            builder: (context) {
              ct = context.ct;
              return const SizedBox();
            },
          ),
        ),
      );
      expect(ct.colors.background, clientThemeConfig.dark.background);
    });
  });

  group('usesClientTheme', () {
    test('signed out and restoring use the client theme', () {
      expect(usesClientTheme(const Unauthenticated()), isTrue);
      expect(usesClientTheme(const AuthRestoring()), isTrue);
    });

    test('a trainer keeps the earlier theme', () {
      expect(usesClientTheme(Authenticated(_session(AppRole.client))), isTrue);
      expect(
        usesClientTheme(Authenticated(_session(AppRole.trainer))),
        isFalse,
      );
      expect(
        usesClientTheme(
          Authenticated(_session(AppRole.trainer), choosingRole: true),
        ),
        isTrue,
      );
    });
  });

  group('ThemeModeController', () {
    test('follows the system until a mode is stored', () async {
      final container = ProviderContainer(
        overrides: [
          themeModeStoreProvider.overrideWithValue(InMemoryThemeModeStore()),
        ],
      );
      addTearDown(container.dispose);
      expect(container.read(themeModeProvider), ThemeMode.system);
    });

    test('restores and stores the picked mode', () async {
      final store = InMemoryThemeModeStore(ThemeMode.dark);
      final container = ProviderContainer(
        overrides: [themeModeStoreProvider.overrideWithValue(store)],
      );
      addTearDown(container.dispose);
      container.listen(themeModeProvider, (_, _) {});
      await Future<void>.delayed(Duration.zero);
      expect(container.read(themeModeProvider), ThemeMode.dark);

      await container.read(themeModeProvider.notifier).set(ThemeMode.light);
      expect(container.read(themeModeProvider), ThemeMode.light);
      expect(store.value, ThemeMode.light);
    });

    test('the secure store reads back what it wrote', () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final store = container.read(themeModeStoreProvider);
      expect(await store.read(), isNull);
      await store.write(ThemeMode.dark);
      expect(await store.read(), ThemeMode.dark);
    });
  });
}
