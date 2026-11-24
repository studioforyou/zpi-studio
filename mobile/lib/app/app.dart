import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/intro/login_intro.dart';
import 'package:mobile/app/push/push_notifications.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/app/theme/app_theme.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/app/theme/client/theme_mode.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class App extends ConsumerWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.watch(pushNotificationsProvider);
    final client = usesClientTheme(ref.watch(authControllerProvider));
    return MaterialApp.router(
      scaffoldMessengerKey: notificationMessengerKey,
      routerConfig: ref.watch(appRouterProvider),
      builder: (_, child) => LoginIntro(child: child!),
      // The redesigned theme is for the client role only (#281). Trainer
      // screens keep the earlier theme until their own redesign.
      theme: client ? _clientLight : _trainerTheme,
      darkTheme: client ? _clientDark : null,
      themeMode: client ? ref.watch(themeModeProvider) : ThemeMode.light,
      // No cross-fade: the client and trainer themes have different text
      // styles, which Flutter cannot interpolate.
      themeAnimationDuration: Duration.zero,
      onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('pl'),
      debugShowCheckedModeBanner: false,
    );
  }
}

final _clientLight = buildClientTheme(Brightness.light);
final _clientDark = buildClientTheme(Brightness.dark);
final _trainerTheme = buildAppTheme();

/// Whether the screens for [auth] use the client theme: everything except a
/// signed-in trainer, so sign-in and the role picker look like the client app.
bool usesClientTheme(AuthState auth) => switch (auth) {
  Authenticated(:final session, :final choosingRole) =>
    choosingRole || session.activeRole != AppRole.trainer,
  _ => true,
};
