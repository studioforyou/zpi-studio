import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/ping/ping_screen.dart';
import 'package:mobile/app/shell/role_shell.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/presentation/login_screen.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/passes/presentation/passes_screen.dart';
import 'package:mobile/features/schedule/presentation/availability_screen.dart';
import 'package:mobile/features/schedule/presentation/schedule_screen.dart';
import 'package:mobile/features/trainings/presentation/reservations_screen.dart';
import 'package:mobile/features/trainings/presentation/trainings_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const loginPath = '/login';
const pingPath = '/ping';

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
    (l10n) => l10n.navSchedule,
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
    (l10n) => l10n.navReservations,
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
    const ScheduleScreen(),
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

  final router = GoRouter(
    initialLocation: loginPath,
    refreshListenable: notifier,
    redirect: (context, state) {
      final location = state.matchedLocation;
      switch (ref.read(authControllerProvider)) {
        case Unauthenticated():
          return location == loginPath || location == pingPath
              ? null
              : loginPath;
        case Authenticated(:final session):
          final role = session.activeRole.name;
          if (location == loginPath || !location.startsWith('/$role/')) {
            return '/$role/schedule';
          }
          return null;
      }
    },
    routes: [
      GoRoute(path: loginPath, builder: (_, _) => const LoginScreen()),
      GoRoute(path: pingPath, builder: (_, _) => const PingScreen()),
      _clientShell(),
      _trainerShell(),
    ],
  );

  ref.onDispose(notifier.dispose);
  ref.onDispose(router.dispose);
  return router;
});
