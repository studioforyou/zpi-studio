import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_card_view.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const TraineeOverviewQuery _query = (
  scope: (accountId: 'account', staffId: 'staff'),
  customerId: 'customer',
);

const _conflict = TraineeCardException(
  statusCode: 409,
  code: 'VERSION_CONFLICT',
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

HealthNote _note({
  String id = 'note',
  HealthNoteKind kind = HealthNoteKind.injury,
  String description = 'Uraz lewego kolana – unikać skoków.',
  String? reportedBy = 'Tomasz Lewandowski',
  DateTime? archivedAt,
  String? archivedBy,
}) => HealthNote(
  id: id,
  kind: kind,
  description: description,
  reportedAt: DateTime(2026, 10, 20, 9),
  reportedBy: reportedBy,
  archivedAt: archivedAt,
  archivedBy: archivedBy,
);

TraineeCard _card({
  String? goals,
  String? experience,
  int version = 0,
  String? updatedBy,
  HealthAccess access = HealthAccess.granted,
  List<HealthNote> notes = const [],
}) => TraineeCard(
  customerId: 'customer',
  goals: goals,
  sportExperience: experience,
  version: version,
  updatedAt: version == 0 ? null : DateTime(2026, 11, 1, 10),
  updatedBy: updatedBy,
  healthAccess: access,
  healthNotes: notes,
);

class _CardApi implements TraineeCardApi {
  _CardApi(this.card);

  TraineeCard card;
  Object? fetchError;

  /// The next update fails with a conflict, after this card was saved.
  TraineeCard? savedMeanwhile;
  Object? addError;
  final updates = <({String goals, String experience, int version})>[];
  final added = <({HealthNoteKind kind, String description})>[];
  final archived = <String>[];

  @override
  Future<TraineeCard> fetch(String customerId) async {
    if (fetchError case final error?) throw error;
    return card;
  }

  @override
  Future<TraineeCard> update(
    String customerId, {
    required String goals,
    required String sportExperience,
    required int version,
  }) async {
    updates.add((goals: goals, experience: sportExperience, version: version));
    if (savedMeanwhile case final newer?) {
      savedMeanwhile = null;
      card = newer;
      throw _conflict;
    }
    String? text(String value) => value.trim().isEmpty ? null : value.trim();
    return card = _card(
      goals: text(goals),
      experience: text(sportExperience),
      version: version + 1,
      updatedBy: 'Marek Nowak',
      access: card.healthAccess,
      notes: card.healthNotes,
    );
  }

  @override
  Future<HealthNote> addHealthNote(
    String customerId, {
    required HealthNoteKind kind,
    required String description,
  }) async {
    added.add((kind: kind, description: description));
    if (addError case final error?) throw error;
    final note = _note(
      id: 'added',
      kind: kind,
      description: description,
      reportedBy: 'Marek Nowak',
    );
    card = _card(
      goals: card.goals,
      experience: card.sportExperience,
      version: card.version,
      access: card.healthAccess,
      notes: [note, ...card.healthNotes],
    );
    return note;
  }

  @override
  Future<HealthNote> archiveHealthNote(String customerId, String noteId) async {
    archived.add(noteId);
    final notes = [
      for (final note in card.healthNotes)
        note.id == noteId
            ? _note(
                id: note.id,
                kind: note.kind,
                description: note.description,
                reportedBy: note.reportedBy,
                archivedAt: DateTime(2026, 11, 11, 12),
                archivedBy: 'Marek Nowak',
              )
            : note,
    ];
    card = _card(
      goals: card.goals,
      experience: card.sportExperience,
      version: card.version,
      access: card.healthAccess,
      notes: notes,
    );
    return notes.firstWhere((note) => note.id == noteId);
  }
}

class _Adapter implements HttpClientAdapter {
  Map<String, dynamic> body = {};
  int status = 200;
  final calls = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add(options);
    return ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Future<void> _pump(
  WidgetTester tester,
  _CardApi api, {
  Size size = const Size(600, 1400),
  double scale = 1,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      traineeCardApiProvider.overrideWithValue(api),
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
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: const Scaffold(body: TraineeCardView(query: _query)),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  test('reads and changes the card through its contract', () async {
    final adapter = _Adapter();
    final api = TraineeCardApi(Dio()..httpClientAdapter = adapter);
    adapter.body = {
      'customerId': 'customer',
      'goals': 'Szpagat.',
      'sportExperience': null,
      'version': 3,
      'updatedAt': '2026-11-01T10:00:00Z',
      'updatedBy': 'Ewa Wiśniewska',
      'healthAccess': 'GRANTED',
      'healthNotes': [
        {
          'id': 'note',
          'kind': 'CONTRAINDICATION',
          'description': 'Bez wiszenia.',
          'reportedAt': '2026-10-20T09:00:00Z',
          'reportedBy': 'Marek Nowak',
          'archivedAt': '2026-11-02T09:00:00Z',
          'archivedBy': null,
        },
      ],
    };
    final card = await api.fetch('customer');
    expect(adapter.calls.last.method, 'GET');
    expect(adapter.calls.last.path, '/api/v1/me/trainees/customer/card');
    expect(card.goals, 'Szpagat.');
    expect(card.sportExperience, isNull);
    expect(card.version, 3);
    expect(card.updatedAt!.isUtc, isFalse);
    expect(card.healthAccess, HealthAccess.granted);
    expect(card.currentHealthNotes, isEmpty);
    final note = card.archivedHealthNotes.single;
    expect(note.kind, HealthNoteKind.contraindication);
    expect(note.archivedBy, isNull);

    adapter.body = {
      ...adapter.body,
      'healthAccess': 'NOT_SHARED',
      'healthNotes': null,
    };
    final hidden = await api.update(
      'customer',
      goals: '  Szpagat poprzeczny. ',
      sportExperience: ' ',
      version: 3,
    );
    expect(adapter.calls.last.method, 'PATCH');
    expect(adapter.calls.last.path, '/api/v1/me/trainees/customer/card');
    expect(adapter.calls.last.data, {
      'goals': 'Szpagat poprzeczny.',
      'sportExperience': '',
      'version': 3,
    });
    expect(hidden.healthAccess, HealthAccess.notShared);
    expect(hidden.healthNotes, isEmpty);
    // An unknown access never shows notes.
    expect(HealthAccess.fromJson('SOMETHING_NEW'), HealthAccess.noPermission);

    adapter.body = {
      'id': 'note',
      'kind': 'INJURY',
      'description': 'Uraz kolana.',
      'reportedAt': '2026-11-11T09:00:00Z',
      'reportedBy': 'Marek Nowak',
      'archivedAt': null,
      'archivedBy': null,
    };
    await api.addHealthNote(
      'customer',
      kind: HealthNoteKind.injury,
      description: ' Uraz kolana. ',
    );
    expect(adapter.calls.last.method, 'POST');
    expect(
      adapter.calls.last.path,
      '/api/v1/me/trainees/customer/health-notes',
    );
    expect(adapter.calls.last.data, {
      'kind': 'INJURY',
      'description': 'Uraz kolana.',
    });
    await api.archiveHealthNote('customer', 'note');
    expect(adapter.calls.last.method, 'POST');
    expect(
      adapter.calls.last.path,
      '/api/v1/me/trainees/customer/health-notes/note/archive',
    );

    adapter.status = 409;
    adapter.body = {'code': 'VERSION_CONFLICT'};
    await expectLater(
      api.update('customer', goals: '', sportExperience: '', version: 1),
      throwsA(
        isA<TraineeCardException>().having(
          (error) => error.isVersionConflict,
          'conflict',
          isTrue,
        ),
      ),
    );
  });

  testWidgets('shows goals, experience and the shared health notes', (
    tester,
  ) async {
    await _pump(
      tester,
      _CardApi(
        _card(
          goals: 'Pierwsze podciągnięcie na drążku.',
          experience: 'Siłownia 2 lata.',
          version: 2,
          updatedBy: 'Ewa Wiśniewska',
          notes: [
            _note(),
            _note(
              id: 'old',
              kind: HealthNoteKind.contraindication,
              description: 'Bez wiszenia przez 2 tygodnie.',
              reportedBy: 'Marek Nowak',
              archivedAt: DateTime(2026, 9, 27, 12),
              archivedBy: 'Marek Nowak',
            ),
          ],
        ),
      ),
    );

    expect(find.text('Pierwsze podciągnięcie na drążku.'), findsOneWidget);
    expect(find.text('Siłownia 2 lata.'), findsOneWidget);
    expect(
      find.text('Ostatnia zmiana: 01.11.2026, Ewa Wiśniewska'),
      findsOneWidget,
    );
    expect(find.text('Uraz'), findsOneWidget);
    expect(find.text('Uraz lewego kolana – unikać skoków.'), findsOneWidget);
    expect(find.text('Dodano 20.10.2026 · Tomasz Lewandowski'), findsOneWidget);
    expect(find.text('Bez wiszenia przez 2 tygodnie.'), findsNothing);

    await tester.tap(find.text('Archiwalne (1)'));
    await tester.pumpAndSettle();
    expect(find.text('Bez wiszenia przez 2 tygodnie.'), findsOneWidget);
    expect(
      find.text('Zarchiwizowano 27.09.2026 · Marek Nowak'),
      findsOneWidget,
    );
    // Archived notes cannot be archived again.
    expect(find.byTooltip('Archiwizuj'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('explains why the health notes are hidden', (tester) async {
    final api = _CardApi(
      _card(goals: 'Szpagat.', version: 1, access: HealthAccess.notShared),
    );
    await _pump(tester, api);
    expect(
      find.text('Dane zdrowotne podopiecznego nie zostały Ci udostępnione.'),
      findsOneWidget,
    );
    expect(find.widgetWithText(TextButton, 'Dodaj'), findsNothing);

    api.card = _card(access: HealthAccess.noPermission);
    await tester.drag(find.byType(RefreshIndicator), const Offset(0, 900));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'Nie masz uprawnienia do danych zdrowotnych podopiecznych. '
        'Może je nadać administrator studia.',
      ),
      findsOneWidget,
    );
    expect(find.widgetWithText(TextButton, 'Dodaj'), findsNothing);
  });

  testWidgets('fills in an empty card', (tester) async {
    final api = _CardApi(_card());
    await _pump(tester, api);
    expect(
      find.text(
        'Nikt jeszcze nie uzupełnił karty. '
        'Dodaj cele i doświadczenie podopiecznego.',
      ),
      findsOneWidget,
    );

    await tester.tap(find.byTooltip('Edytuj cele i doświadczenie'));
    await tester.pumpAndSettle();
    final save = find.widgetWithText(FilledButton, 'Zapisz');
    expect(tester.widget<FilledButton>(save).onPressed, isNull);
    await tester.enterText(
      find.widgetWithText(TextField, 'Cele'),
      ' Redukcja 5 kg. ',
    );
    await tester.pump();
    expect(tester.widget<FilledButton>(save).onPressed, isNotNull);
    await tester.tap(save);
    await tester.pumpAndSettle();

    expect(api.updates.single.version, 0);
    expect(api.updates.single.goals, ' Redukcja 5 kg. ');
    expect(find.text('Zapisano kartę podopiecznego.'), findsOneWidget);
    expect(find.text('Redukcja 5 kg.'), findsOneWidget);
    expect(find.text('Nie uzupełniono'), findsOneWidget);
    expect(
      find.text('Ostatnia zmiana: 01.11.2026, Marek Nowak'),
      findsOneWidget,
    );
  });

  testWidgets('shows a change saved meanwhile and saves on top of it', (
    tester,
  ) async {
    final api = _CardApi(_card(goals: 'Szpagat.', version: 1));
    api.savedMeanwhile = _card(
      goals: 'Szpagat poprzeczny.',
      experience: 'Taniec współczesny.',
      version: 2,
      updatedBy: 'Ewa Wiśniewska',
    );
    await _pump(tester, api);
    await tester.tap(find.byTooltip('Edytuj cele i doświadczenie'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Cele'), 'Mostek.');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('W międzyczasie Ewa Wiśniewska'),
      findsOneWidget,
    );
    // The changed field keeps the trainer's text and shows the saved one.
    expect(find.text('Mostek.'), findsOneWidget);
    expect(find.text('Zapisane teraz: Szpagat poprzeczny.'), findsOneWidget);
    // The untouched field takes the other trainer's change.
    expect(find.text('Taniec współczesny.'), findsOneWidget);
    expect(find.textContaining('Zapisane teraz: Taniec'), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz'));
    await tester.pumpAndSettle();
    expect(api.updates.map((update) => update.version), [1, 2]);
    expect(api.updates.last.goals, 'Mostek.');
    expect(api.updates.last.experience, 'Taniec współczesny.');
    expect(find.text('Mostek.'), findsOneWidget);
    expect(find.text('Taniec współczesny.'), findsOneWidget);
    expect(find.text('Zapisano kartę podopiecznego.'), findsOneWidget);
  });

  testWidgets('adds a health note', (tester) async {
    final api = _CardApi(_card());
    await _pump(tester, api);
    expect(
      find.text('Brak zgłoszonych ograniczeń zdrowotnych.'),
      findsOneWidget,
    );

    await tester.tap(find.widgetWithText(TextButton, 'Dodaj'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz'));
    await tester.pump();
    expect(find.text('Wpisz opis ograniczenia.'), findsOneWidget);
    expect(api.added, isEmpty);

    await tester.tap(find.text('Przeciwwskazanie'));
    await tester.enterText(
      find.widgetWithText(TextField, 'Opis'),
      'Bez wiszenia przez 2 tygodnie.',
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz'));
    await tester.pumpAndSettle();

    expect(api.added.single.kind, HealthNoteKind.contraindication);
    expect(api.added.single.description, 'Bez wiszenia przez 2 tygodnie.');
    expect(find.text('Dodano ograniczenie zdrowotne.'), findsOneWidget);
    expect(find.text('Bez wiszenia przez 2 tygodnie.'), findsOneWidget);
    expect(find.text('Brak zgłoszonych ograniczeń zdrowotnych.'), findsNothing);
  });

  testWidgets('says when the access to health data was lost', (tester) async {
    final api = _CardApi(_card())
      ..addError = const TraineeCardException(
        statusCode: 403,
        code: 'HEALTH_DATA_NOT_SHARED',
      );
    await _pump(tester, api);
    await tester.tap(find.widgetWithText(TextButton, 'Dodaj'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.widgetWithText(TextField, 'Opis'),
      'Uraz barku.',
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz'));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'Nie masz już dostępu do danych zdrowotnych tego podopiecznego.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('archives a note after a confirmation', (tester) async {
    final api = _CardApi(_card(notes: [_note()]));
    await _pump(tester, api);

    await tester.tap(find.byTooltip('Archiwizuj'));
    await tester.pumpAndSettle();
    expect(find.text('Zarchiwizować ograniczenie?'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Anuluj'));
    await tester.pumpAndSettle();
    expect(api.archived, isEmpty);

    await tester.tap(find.byTooltip('Archiwizuj'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Archiwizuj'));
    await tester.pumpAndSettle();
    expect(api.archived, ['note']);
    expect(find.text('Zarchiwizowano ograniczenie.'), findsOneWidget);
    expect(
      find.text('Brak zgłoszonych ograniczeń zdrowotnych.'),
      findsOneWidget,
    );
    expect(find.text('Archiwalne (1)'), findsOneWidget);
  });

  testWidgets('stays usable on a narrow screen with larger text', (
    tester,
  ) async {
    final api = _CardApi(
      _card(
        goals: 'Pierwsze podciągnięcie na drążku i redukcja 5 kg do wakacji.',
        experience: 'Siłownia 2 lata, piłka nożna amatorsko.',
        version: 3,
        updatedBy: 'Katarzyna Zielińska-Wiśniewska',
        notes: [
          _note(kind: HealthNoteKind.contraindication),
          _note(
            id: 'old',
            reportedBy: 'Katarzyna Zielińska-Wiśniewska',
            archivedAt: DateTime(2026, 9, 27),
            archivedBy: 'Katarzyna Zielińska-Wiśniewska',
          ),
        ],
      ),
    );
    await _pump(tester, api, size: const Size(320, 800), scale: 1.3);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Archiwalne (1)'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    await tester.tap(find.widgetWithText(TextButton, 'Dodaj'));
    await tester.pumpAndSettle();
    expect(find.text('Przeciwwskazanie'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('says the card is unavailable when the cooperation ended', (
    tester,
  ) async {
    final api = _CardApi(_card())
      ..fetchError = const TraineeCardException(statusCode: 404);
    await _pump(tester, api);
    expect(
      find.text('Karta jest niedostępna. Współpraca mogła się zakończyć.'),
      findsOneWidget,
    );

    api.fetchError = null;
    await tester.tap(find.widgetWithText(OutlinedButton, 'Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('Cele i doświadczenie'), findsOneWidget);
  });
}
