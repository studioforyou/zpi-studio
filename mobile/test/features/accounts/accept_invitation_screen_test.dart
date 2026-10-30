import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/invitation_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

const _session = Session(
  accountId: 'a1',
  displayName: 'Jan Testowy',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [Profile(id: 'p1', displayName: 'Jan Testowy', isChild: false)],
  activeProfileId: 'p1',
);

final _details = InvitationDetails(
  email: 'jan@example.com',
  displayName: 'Jan Testowy',
  roles: const ['CLIENT'],
  expiresAt: DateTime.utc(2026, 10, 30),
);

class _FakeInvitationApi implements InvitationApi {
  _FakeInvitationApi({this.fetchFailure, this.acceptFailure});

  final InvitationFailureReason? fetchFailure;
  final InvitationFailureReason? acceptFailure;
  final fetched = <String>[];
  final accepted = <(String, String)>[];

  @override
  Future<InvitationDetails> fetch(String token) async {
    fetched.add(token);
    final reason = fetchFailure;
    if (reason != null) {
      throw InvitationFailure(reason);
    }
    return _details;
  }

  @override
  Future<void> accept(String token, String password) async {
    final reason = acceptFailure;
    if (reason != null) {
      throw InvitationFailure(reason);
    }
    accepted.add((token, password));
  }
}

class _AuthRepository implements AuthRepository {
  _AuthRepository({Future<Session?>? restored, this.loginFails = false})
    : _restored = restored ?? Future.value(null);

  final Future<Session?> _restored;
  final bool loginFails;
  final logins = <(String, String)>[];

  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    logins.add((email, password));
    if (loginFails) {
      throw const AuthFailure(AuthFailureReason.network);
    }
    return _session;
  }

  @override
  Future<Session?> restore() => _restored;

  @override
  Future<void> logout() async {}

  @override
  Future<void> sendPasswordReset(String email) async {}
}

void main() {
  Future<ProviderContainer> pumpApp(
    WidgetTester tester, {
    required InvitationApi api,
    AuthRepository? auth,
    String? location = '$invitePath?token=abc',
  }) async {
    final container = ProviderContainer(
      overrides: [
        authRepositoryProvider.overrideWithValue(auth ?? _AuthRepository()),
        invitationApiProvider.overrideWithValue(api),
        groupClassesProvider.overrideWith((ref, query) async => const []),
        appConfigProvider.overrideWithValue(
          const AppConfig(
            flavor: AppFlavor.dev,
            apiBaseUrl: 'http://localhost',
          ),
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const App()),
    );
    await tester.pumpAndSettle();
    if (location != null) {
      container.read(appRouterProvider).go(location);
      await tester.pumpAndSettle();
    }
    return container;
  }

  Future<void> enterPasswords(
    WidgetTester tester,
    String password, [
    String? repeat,
  ]) async {
    await tester.enterText(find.byType(TextField).at(0), password);
    await tester.enterText(find.byType(TextField).at(1), repeat ?? password);
    await tester.tap(find.text('Aktywuj konto').last);
    await tester.pumpAndSettle();
  }

  testWidgets('greets the invited person by name, role and e-mail', (
    tester,
  ) async {
    final api = _FakeInvitationApi();
    await pumpApp(tester, api: api);

    expect(api.fetched, ['abc']);
    expect(
      find.text(
        'Cześć Jan! Studio zaprasza Cię jako klienta. '
        'Ustaw hasło do konta jan@example.com.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('activates the account and signs the person in', (tester) async {
    final api = _FakeInvitationApi();
    final auth = _AuthRepository();
    final container = await pumpApp(tester, api: api, auth: auth);

    await enterPasswords(tester, 'Haslo1234');

    expect(api.accepted, [('abc', 'Haslo1234')]);
    expect(auth.logins, [('jan@example.com', 'Haslo1234')]);
    expect(
      container.read(appRouterProvider).state.matchedLocation,
      clientSchedulePath,
    );
  });

  for (final (name, password, repeat, message) in [
    ('a short password', 'Haslo12', 'Haslo12', 'co najmniej 8 znaków'),
    ('different passwords', 'Haslo1234', 'Haslo4321', 'nie są takie same'),
  ]) {
    testWidgets('rejects $name without a request', (tester) async {
      final api = _FakeInvitationApi();
      await pumpApp(tester, api: api);

      await enterPasswords(tester, password, repeat);

      expect(find.textContaining(message), findsOneWidget);
      expect(api.accepted, isEmpty);
    });
  }

  testWidgets('the layout does not move when a message appears', (
    tester,
  ) async {
    await pumpApp(tester, api: _FakeInvitationApi());
    final button = tester.getTopLeft(find.byType(FilledButton));
    final field = tester.getTopLeft(find.byType(TextField).first);

    await enterPasswords(tester, 'short');

    expect(tester.getTopLeft(find.byType(FilledButton)), button);
    expect(tester.getTopLeft(find.byType(TextField).first), field);
  });

  testWidgets('keeps the fields and the button of the login screen', (
    tester,
  ) async {
    final container = await pumpApp(
      tester,
      api: _FakeInvitationApi(),
      location: null,
    );
    final loginFields = [
      for (final i in [0, 1]) tester.getTopLeft(find.byType(TextField).at(i)),
    ];
    final loginButton = tester.getTopLeft(find.byType(FilledButton));

    container.read(appRouterProvider).go('$invitePath?token=abc');
    await tester.pumpAndSettle();

    expect(tester.getTopLeft(find.byType(TextField).at(0)), loginFields[0]);
    expect(tester.getTopLeft(find.byType(TextField).at(1)), loginFields[1]);
    expect(tester.getTopLeft(find.byType(FilledButton)), loginButton);
  });

  for (final (reason, title) in [
    (InvitationFailureReason.expired, 'Zaproszenie wygasło'),
    (InvitationFailureReason.used, 'Zaproszenie zostało już użyte'),
    (InvitationFailureReason.notFound, 'Nieprawidłowy link'),
  ]) {
    testWidgets('explains a link that cannot be used: ${reason.name}', (
      tester,
    ) async {
      await pumpApp(tester, api: _FakeInvitationApi(fetchFailure: reason));

      expect(find.text(title), findsOneWidget);
      await tester.tap(find.text('Przejdź do logowania'));
      await tester.pumpAndSettle();
      expect(find.text('Zaloguj się'), findsOneWidget);
    });
  }

  testWidgets('a link without a token is not valid', (tester) async {
    final api = _FakeInvitationApi();
    await pumpApp(tester, api: api, location: invitePath);

    expect(find.text('Nieprawidłowy link'), findsOneWidget);
    expect(api.fetched, isEmpty);
  });

  testWidgets('an invitation used in the meantime cannot be accepted', (
    tester,
  ) async {
    await pumpApp(
      tester,
      api: _FakeInvitationApi(acceptFailure: InvitationFailureReason.used),
    );

    await enterPasswords(tester, 'Haslo1234');

    expect(find.text('Zaproszenie zostało już użyte'), findsOneWidget);
  });

  testWidgets('a network error while loading can be retried', (tester) async {
    final api = _FakeInvitationApi(
      fetchFailure: InvitationFailureReason.network,
    );
    await pumpApp(tester, api: api);

    expect(find.text('Nie udało się wczytać zaproszenia'), findsOneWidget);
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(api.fetched, ['abc', 'abc']);
  });

  testWidgets('sends to the login screen when signing in afterwards fails', (
    tester,
  ) async {
    await pumpApp(
      tester,
      api: _FakeInvitationApi(),
      auth: _AuthRepository(loginFails: true),
    );

    await enterPasswords(tester, 'Haslo1234');

    expect(find.text('Konto aktywne'), findsOneWidget);
  });

  testWidgets('a link that opens the app keeps its token through the splash', (
    tester,
  ) async {
    final restored = Completer<Session?>();
    final api = _FakeInvitationApi();
    final container = ProviderContainer(
      overrides: [
        authRepositoryProvider.overrideWithValue(
          _AuthRepository(restored: restored.future),
        ),
        invitationApiProvider.overrideWithValue(api),
        groupClassesProvider.overrideWith((ref, query) async => const []),
        appConfigProvider.overrideWithValue(
          const AppConfig(
            flavor: AppFlavor.dev,
            apiBaseUrl: 'http://localhost',
          ),
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const App()),
    );
    container.read(appRouterProvider).go('$invitePath?token=xyz');
    await tester.pump();
    expect(container.read(appRouterProvider).state.matchedLocation, splashPath);

    restored.complete(null);
    await tester.pumpAndSettle();

    expect(container.read(appRouterProvider).state.matchedLocation, invitePath);
    expect(api.fetched, ['xyz']);
  });

  testWidgets('a signed-in user opening a link stays in the app', (
    tester,
  ) async {
    final api = _FakeInvitationApi();
    final container = await pumpApp(
      tester,
      api: api,
      auth: _AuthRepository(restored: Future.value(_session)),
    );

    expect(
      container.read(appRouterProvider).state.matchedLocation,
      clientSchedulePath,
    );
    expect(api.fetched, isEmpty);
  });
}
