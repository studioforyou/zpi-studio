import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/accounts/presentation/customer_profile_edit_screen.dart';

const _session = Session(
  accountId: 'account-1',
  displayName: 'Anna Kowalska',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Kowalska', isChild: false),
    Profile(id: 'child', displayName: 'Jan Kowalski', isChild: true),
  ],
  activeProfileId: 'self',
);

class _SignedInRepository implements AuthRepository {
  @override
  Future<Session?> restore() async => _session;

  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async => _session;

  @override
  Future<void> logout() async {}

  @override
  Future<void> sendPasswordReset(String email) async {}
}

/// Answers profile requests and an empty list for anything else the client
/// shell loads on start.
class _ProfileAdapter implements HttpClientAdapter {
  int saveStatus = 200;
  final requests = <RequestOptions>[];
  final patches = <Map<String, dynamic>>[];
  final profiles = <String, Map<String, dynamic>>{
    'self': {
      'id': 'self',
      'kind': 'ADULT',
      'firstName': 'Anna',
      'lastName': 'Kowalska',
      'birthDate': '1990-05-17',
      'phone': '600100200',
      'emergencyContactName': null,
      'emergencyPhone': null,
    },
    'child': {
      'id': 'child',
      'kind': 'CHILD',
      'firstName': 'Jan',
      'lastName': 'Kowalski',
      'birthDate': null,
      'phone': null,
      'emergencyContactName': null,
      'emergencyPhone': null,
    },
  };

  ResponseBody json(Object body, int status) => ResponseBody.fromString(
    jsonEncode(body),
    status,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    const prefix = '/api/v1/me/customer-profiles/';
    if (!options.path.startsWith(prefix)) {
      return json({'items': <Object>[]}, 200);
    }
    requests.add(options);
    final id = options.path.substring(prefix.length);
    if (options.method == 'GET') {
      return json(profiles[id]!, 200);
    }
    final patch = Map<String, dynamic>.from(options.data as Map);
    patches.add(patch);
    if (saveStatus != 200) {
      return json({'code': 'VALIDATION_FAILED'}, saveStatus);
    }
    profiles[id] = {...profiles[id]!, ...patch};
    return json(profiles[id]!, 200);
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _ProfileAdapter adapter;
  late Dio dio;

  setUp(() {
    adapter = _ProfileAdapter();
    dio = Dio()..httpClientAdapter = adapter;
  });

  tearDown(() => dio.close(force: true));

  Future<ProviderContainer> openEditor(
    WidgetTester tester, {
    String profileId = 'self',
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(_SignedInRepository()),
          dioProvider.overrideWithValue(dio),
        ],
        child: const App(),
      ),
    );
    await tester.pumpAndSettle();
    final container = ProviderScope.containerOf(
      tester.element(find.byType(NavigationBar)),
    );
    container.read(authControllerProvider.notifier).selectProfile(profileId);
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('Profil'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Dane osobowe'));
    await tester.pumpAndSettle();
    return container;
  }

  Finder field(String label) => find.widgetWithText(TextFormField, label);

  Future<void> tapSave(WidgetTester tester) async {
    await tester.ensureVisible(find.text('Zapisz zmiany'));
    await tester.tap(find.text('Zapisz zmiany'));
    await tester.pumpAndSettle();
  }

  testWidgets('loads the active profile into the form', (tester) async {
    await openEditor(tester);

    expect(find.byType(CustomerProfileEditScreen), findsOneWidget);
    expect(adapter.requests.single.path, '/api/v1/me/customer-profiles/self');
    expect(find.text('Anna'), findsOneWidget);
    expect(find.text('Kowalska'), findsOneWidget);
    expect(find.text('600100200'), findsOneWidget);
    expect(find.text('17.05.1990'), findsOneWidget);
  });

  testWidgets('saves the details and shows the new name', (tester) async {
    final container = await openEditor(tester);

    await tester.enterText(field('Imię'), ' Alicja ');
    await tester.enterText(field('Imię i nazwisko'), 'Marek Nowak');
    await tester.enterText(field('Telefon'), '601 200 300');
    await tapSave(tester);

    expect(adapter.patches.single, {
      'firstName': 'Alicja',
      'lastName': 'Kowalska',
      'birthDate': '1990-05-17',
      'phone': '600100200',
      'emergencyContactName': 'Marek Nowak',
      'emergencyPhone': '601 200 300',
    });
    expect(find.text('Zmiany zostały zapisane.'), findsOneWidget);
    expect(find.byType(CustomerProfileEditScreen), findsNothing);
    final session =
        (container.read(authControllerProvider) as Authenticated).session;
    expect(session.displayName, 'Alicja Kowalska');
    expect(session.profiles.first.displayName, 'Alicja Kowalska');
  });

  testWidgets('a guardian edits the active child profile', (tester) async {
    final container = await openEditor(tester, profileId: 'child');

    expect(adapter.requests.single.path, '/api/v1/me/customer-profiles/child');
    expect(find.text('Edytujesz dane dziecka: Jan Kowalski.'), findsOneWidget);
    await tester.enterText(field('Imię'), 'Jasiek');
    await tapSave(tester);

    expect(adapter.patches.single['firstName'], 'Jasiek');
    final session =
        (container.read(authControllerProvider) as Authenticated).session;
    expect(session.displayName, 'Anna Kowalska');
    expect(session.profiles.last.displayName, 'Jasiek Kowalski');
  });

  testWidgets('validates fields before sending anything', (tester) async {
    await openEditor(tester);

    await tester.enterText(field('Imię'), '   ');
    await tester.enterText(field('Telefon (opcjonalnie)'), 'abc');
    await tester.enterText(field('Imię i nazwisko'), 'Marek');
    await tapSave(tester);

    expect(adapter.patches, isEmpty);
    expect(find.text('To pole jest wymagane.'), findsOneWidget);
    expect(
      find.text('Wpisz numer z cyframi, np. +48 600 100 200.'),
      findsOneWidget,
    );
    expect(find.text('Podaj telefon osoby do kontaktu.'), findsOneWidget);
  });

  testWidgets('clears the birth date', (tester) async {
    await openEditor(tester);

    await tester.tap(find.byTooltip('Usuń datę urodzenia'));
    await tester.pump();
    await tapSave(tester);

    expect(adapter.patches.single['birthDate'], isNull);
  });

  testWidgets('shows a server validation error and stays open', (tester) async {
    adapter.saveStatus = 400;
    await openEditor(tester);

    await tapSave(tester);

    expect(
      find.text('Sprawdź wpisane dane i spróbuj ponownie.'),
      findsOneWidget,
    );
    expect(find.byType(CustomerProfileEditScreen), findsOneWidget);
  });
}
