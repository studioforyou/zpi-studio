import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/intro/login_intro.dart';
import 'package:mobile/app/ping/ping_screen.dart';
import 'package:mobile/app/shell/role_shell.dart';
import 'package:mobile/app/splash/splash_screen.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/presentation/accept_invitation_screen.dart';
import 'package:mobile/features/accounts/presentation/login_screen.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/features/accounts/presentation/reset_password_screen.dart';
import 'package:mobile/features/accounts/presentation/staff_profile_edit_screen.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/passes/presentation/passes_screen.dart';
import 'package:mobile/features/schedule/presentation/availability_screen.dart';
import 'package:mobile/features/schedule/presentation/schedule_screen.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_screen.dart';
import 'package:mobile/features/trainings/presentation/reservations_screen.dart';
import 'package:mobile/features/trainings/presentation/trainings_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const splashPath = '/splash';
const loginPath = '/login';
const pingPath = '/ping';
const resetPasswordPath = '/reset-password';

/// Opened from the invitation e-mail link, with the `token` query parameter.
const invitePath = '/invite';

const clientSchedulePath = '/client/schedule';
const clientPassesPath = '/client/passes';
const clientReservationsPath = '/client/reservations';
const clientMessagesPath = '/client/messages';
const clientProfilePath = '/client/profile';

const trainerSchedulePath = '/trainer/schedule';
const trainerTrainingsPath = '/trainer/trainings';
const trainerMessagesPath = '/trainer/messages';
const trainerProfilePath = '/trainer/profile';
const trainerAvailabilitySubPath = 'availability';
const trainerProfileEditSubPath = 'edit';

/// One bottom-navigation tab: its route and its navigation bar entry.
typedef _Tab = ({GoRoute route, ShellDestination destination});

_Tab _tab(
  String path,
  Widget screen,
  IconData icon,
  String Function(AppLocalizations) label, {
  List<RouteBase> routes = const [],
}) => (
  route: GoRoute(path: path, builder: (_, _) => screen, routes: routes),
  destination: ShellDestination(icon: icon, label: label),
);

/// One shell per role, with one branch per tab.
StatefulShellRoute _roleShell(List<_Tab> tabs) {
  final destinations = [for (final tab in tabs) tab.destination];
  return StatefulShellRoute.indexedStack(
    builder: (_, _, navigationShell) =>
        RoleShell(navigationShell: navigationShell, destinations: destinations),
    branches: [
      for (final tab in tabs) StatefulShellBranch(routes: [tab.route]),
    ],
  );
}

StatefulShellRoute _clientShell() => _roleShell([
  _tab(
    clientSchedulePath,
    const ScheduleScreen(),
    Icons.calendar_month,
    (l10n) => l10n.navClassOffer,
  ),
  _tab(
    clientPassesPath,
    const PassesScreen(),
    Icons.card_membership,
    (l10n) => l10n.navPasses,
  ),
  _tab(
    clientReservationsPath,
    const ReservationsScreen(),
    Icons.event_available,
    (l10n) => l10n.navMySchedule,
  ),
  _tab(
    clientMessagesPath,
    const MessagesScreen(),
    Icons.chat_bubble_outline,
    (l10n) => l10n.navMessages,
  ),
  _tab(
    clientProfilePath,
    const ProfileScreen(),
    Icons.person,
    (l10n) => l10n.navProfile,
  ),
]);

StatefulShellRoute _trainerShell() => _roleShell([
  _tab(
    trainerSchedulePath,
    const TrainerScheduleScreen(),
    Icons.calendar_month,
    (l10n) => l10n.navSchedule,
  ),
  _tab(
    trainerTrainingsPath,
    const TrainingsScreen(),
    Icons.fitness_center,
    (l10n) => l10n.navTrainings,
  ),
  _tab(
    trainerMessagesPath,
    const MessagesScreen(),
    Icons.chat_bubble_outline,
    (l10n) => l10n.navMessages,
  ),
  _tab(
    trainerProfilePath,
    const ProfileScreen(),
    Icons.person,
    (l10n) => l10n.navProfile,
    routes: [
      GoRoute(
        path: trainerProfileEditSubPath,
        builder: (_, _) => const StaffProfileEditScreen(),
      ),
      // Opened with context.push('/trainer/profile/availability').
      GoRoute(
        path: trainerAvailabilitySubPath,
        builder: (_, _) => const AvailabilityScreen(),
      ),
    ],
  ),
]);

/// Lets the router re-run `redirect` on auth changes without being rebuilt.
class _RouterRefreshNotifier extends ChangeNotifier {
  void refresh() => notifyListeners();
}

final appRouterProvider = Provider<GoRouter>((ref) {
  final notifier = _RouterRefreshNotifier();
  ref.listen(authControllerProvider, (_, _) => notifier.refresh());
  ref.listen(loginCoveredProvider, (_, _) => notifier.refresh());

  final router = GoRouter(
    initialLocation: splashPath,
    refreshListenable: notifier,
    redirect: (context, state) {
      final location = state.matchedLocation;
      switch (ref.read(authControllerProvider)) {
        case AuthRestoring():
          if (location == splashPath) {
            return null;
          }
          // An invitation link opened the app: come back to it once the
          // session is known.
          return location == invitePath
              ? Uri(
                  path: splashPath,
                  queryParameters: {'from': state.uri.toString()},
                ).toString()
              : splashPath;
        case Unauthenticated():
          final from = state.uri.queryParameters['from'];
          if (location == splashPath &&
              from != null &&
              Uri.parse(from).path == invitePath) {
            return from;
          }
          return location == loginPath ||
                  location == resetPasswordPath ||
                  location == invitePath ||
                  location == pingPath
              ? null
              : loginPath;
        case Authenticated(:final session):
          // Just logged in: stay until the intro covers the login screen.
          if (location == loginPath && !ref.read(loginCoveredProvider)) {
            return null;
          }
          final role = session.activeRole.name;
          if (location == loginPath || !location.startsWith('/$role/')) {
            return '/$role/schedule';
          }
          return null;
      }
    },
    routes: [
      GoRoute(path: splashPath, builder: (_, _) => const SplashScreen()),
      GoRoute(path: loginPath, builder: (_, _) => const LoginScreen()),
      GoRoute(
        path: resetPasswordPath,
        builder: (_, state) =>
            ResetPasswordScreen(initialEmail: state.extra as String? ?? ''),
      ),
      GoRoute(
        path: invitePath,
        builder: (_, state) => AcceptInvitationScreen(
          token: state.uri.queryParameters['token'] ?? '',
        ),
      ),
      GoRoute(path: pingPath, builder: (_, _) => const PingScreen()),
      _clientShell(),
      _trainerShell(),
    ],
  );

  ref.onDispose(notifier.dispose);
  ref.onDispose(router.dispose);
  return router;
});
