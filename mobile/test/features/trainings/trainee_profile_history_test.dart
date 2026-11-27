import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainee_profile_history_api.dart';
import 'package:mobile/features/trainings/presentation/trainee_profile_history_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_history_entry.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const TraineeOverviewQuery _query = (
  scope: (accountId: 'account', staffId: 'staff'),
  customerId: 'customer',
);

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(
    Session(
      accountId: 'account',
      displayName: 'Marek Nowak',
      roles: const [AppRole.trainer],
      activeRole: AppRole.trainer,
      staffProfileId: 'staff',
      profiles: const [],
      activeProfileId: null,
    ),
  );
}

class _HistoryApi implements TraineeProfileHistoryApi {
  _HistoryApi(this.history);

  ProfileHistory history;
  int fetches = 0;

  @override
  Future<ProfileHistory> fetch(String customerId) async {
    fetches++;
    return history;
  }
}

final _today = DateTime.now();

ProfileHistory _history({
  HealthAccess access = HealthAccess.granted,
  List<Map<String, dynamic>>? items,
}) => ProfileHistory.fromJson({
  'healthAccess': switch (access) {
    HealthAccess.granted => 'GRANTED',
    HealthAccess.notShared => 'NOT_SHARED',
    HealthAccess.noPermission => 'NO_PERMISSION',
  },
  'items': items ?? _items(access == HealthAccess.granted),
  'page': 0,
  'size': 100,
  'totalItems': 0,
  'totalPages': 1,
});

String _at(int daysAgo, int hour) => DateTime(
  _today.year,
  _today.month,
  _today.day - daysAgo,
  hour,
  12,
).toUtc().toIso8601String();

List<Map<String, dynamic>> _items(bool health) => [
  {
    'id': 5,
    'occurredAt': _at(0, 10),
    'group': 'PERSONAL',
    'action': 'PROFILE_UPDATED',
    'actor': {'name': 'Katarzyna Zielińska', 'role': 'CLIENT'},
    'changes': [
      {'field': 'phone', 'oldValue': '500 100 200', 'newValue': '600 300 400'},
    ],
    'healthNoteId': null,
    'healthNoteKind': null,
  },
  if (health)
    {
      'id': 4,
      'occurredAt': _at(0, 8),
      'group': 'HEALTH',
      'action': 'HEALTH_NOTE_ADDED',
      'actor': {'name': 'Katarzyna Zielińska', 'role': 'CLIENT'},
      'changes': <Object>[],
      'healthNoteId': 'note',
      'healthNoteKind': 'INJURY',
    },
  {
    'id': 3,
    'occurredAt': _at(1, 18),
    'group': 'CARD',
    'action': 'CARD_UPDATED',
    'actor': {'name': 'Marek Nowak', 'role': 'TRAINER'},
    'changes': [
      {
        'field': 'goals',
        'oldValue': 'Redukcja 5 kg.',
        'newValue': 'Pierwsze podciągnięcie, redukcja 5 kg.',
      },
    ],
    'healthNoteId': null,
    'healthNoteKind': null,
  },
  {
    'id': 2,
    'occurredAt': _at(9, 9),
    'group': 'PERSONAL',
    'action': 'PROFILE_UPDATED',
    'actor': {'name': 'Beata Lis', 'role': 'ADMIN'},
    'changes': [
      {
        'field': 'emergencyContactName',
        'oldValue': 'Anna Zielińska',
        'newValue': 'Michał Zieliński',
      },
      {'field': 'birthDate', 'oldValue': null, 'newValue': '1994-03-14'},
    ],
    'healthNoteId': null,
    'healthNoteKind': null,
  },
];

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _HistoryApi api,
  Widget home,
) async {
  tester.view.physicalSize = const Size(600, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      traineeProfileHistoryApiProvider.overrideWithValue(api),
    ],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox());
    container.dispose();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: home,
      ),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  test('reads the history contract', () {
    final history = _history();
    expect(history.healthAccess, HealthAccess.granted);
    expect(history.entries, hasLength(4));
    final health = history.entries[1];
    expect(health.group, ProfileHistoryGroup.health);
    expect(health.healthNoteKind, HealthNoteKind.injury);
    expect(health.actorRole, ProfileHistoryRole.client);
    expect(history.entries.first.changes.single.newValue, '600 300 400');
    expect(history.hasMore, isFalse);
  });

  test('counts only changes newer than the last one seen', () {
    final history = _history();
    expect(unseenCount(history, null), 0);
    expect(unseenCount(history, 3), 2);
    expect(unseenCount(history, 5), 0);
  });

  testWidgets('shows the timeline newest first with old and new values', (
    tester,
  ) async {
    await _pump(
      tester,
      _HistoryApi(_history()),
      const TraineeProfileHistoryScreen(
        query: _query,
        traineeName: 'Katarzyna Zielińska',
      ),
    );

    expect(find.text('Historia zmian'), findsOneWidget);
    expect(find.text('Dziś'), findsOneWidget);
    expect(find.text('Wczoraj'), findsOneWidget);
    expect(find.text('Zmieniono dane kontaktowe'), findsOneWidget);
    expect(find.textContaining('500 100 200'), findsOneWidget);
    expect(find.text('Dodano ograniczenie zdrowotne'), findsOneWidget);
    expect(find.text('Uraz'), findsOneWidget);
    expect(find.text('Zmieniono cele'), findsOneWidget);
    expect(find.text('Poprzednio: Redukcja 5 kg.'), findsOneWidget);
    expect(find.text('Zmieniono dane osobowe'), findsOneWidget);
    expect(find.textContaining('14.03.1994'), findsOneWidget);
    expect(find.textContaining('Beata Lis (administrator)'), findsOneWidget);
    expect(find.textContaining('Marek Nowak (trener)'), findsOneWidget);
    expect(find.text('Zdrowie'), findsOneWidget);
  });

  testWidgets('filters the entries by group', (tester) async {
    await _pump(
      tester,
      _HistoryApi(_history()),
      const TraineeProfileHistoryScreen(
        query: _query,
        traineeName: 'Katarzyna Zielińska',
      ),
    );

    await tester.tap(find.text('Karta'));
    await tester.pumpAndSettle();
    expect(find.text('Zmieniono cele'), findsOneWidget);
    expect(find.text('Zmieniono dane kontaktowe'), findsNothing);

    await tester.tap(find.text('Zdrowie'));
    await tester.pumpAndSettle();
    expect(find.text('Dodano ograniczenie zdrowotne'), findsOneWidget);
    expect(find.text('Zmieniono cele'), findsNothing);
  });

  testWidgets('without health access the health filter is hidden', (
    tester,
  ) async {
    await _pump(
      tester,
      _HistoryApi(_history(access: HealthAccess.notShared)),
      const TraineeProfileHistoryScreen(
        query: _query,
        traineeName: 'Zosia Wiśniewska',
      ),
    );

    expect(find.text('Zdrowie'), findsNothing);
    expect(
      find.text(
        'Zmiany ograniczeń zdrowotnych widzisz tylko, gdy podopieczny '
        'udostępnił Ci dane zdrowotne.',
      ),
      findsOneWidget,
    );
    expect(find.text('Dodano ograniczenie zdrowotne'), findsNothing);
  });

  testWidgets('an empty history says nobody changed anything', (tester) async {
    await _pump(
      tester,
      _HistoryApi(_history(items: [])),
      const TraineeProfileHistoryScreen(
        query: _query,
        traineeName: 'Paweł Kowalczyk',
      ),
    );

    expect(find.text('Brak zmian'), findsOneWidget);
  });

  testWidgets('the entry marks new changes until the history is opened', (
    tester,
  ) async {
    FlutterSecureStorage.setMockInitialValues({
      'profile_history_seen.account.customer': '3',
    });
    await _pump(
      tester,
      _HistoryApi(_history()),
      const Scaffold(
        body: TraineeHistoryEntry(
          query: _query,
          traineeName: 'Katarzyna Zielińska',
        ),
      ),
    );

    expect(find.text('2 nowe'), findsOneWidget);
    expect(
      find.textContaining('Ostatnia zmiana: dziś, 10:12 · Katarzyna'),
      findsOneWidget,
    );

    await tester.tap(find.text('Historia zmian'));
    await tester.pumpAndSettle();
    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    await tester.pumpAndSettle();

    expect(find.text('2 nowe'), findsNothing);
  });
}
