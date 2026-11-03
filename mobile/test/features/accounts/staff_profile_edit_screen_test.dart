import 'dart:async';
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
import 'package:mobile/features/accounts/presentation/staff_profile_edit_screen.dart';
import 'package:mobile/features/accounts/presentation/widgets/choice_tile.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_header.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

const _session = Session(
  accountId: 'account-1',
  displayName: 'Client Name',
  roles: [AppRole.client, AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [
    Profile(id: 'self', displayName: 'Client Name', isChild: false),
    Profile(id: 'child', displayName: 'Child Name', isChild: true),
  ],
  activeProfileId: 'child',
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

class _ProfileAdapter implements HttpClientAdapter {
  int fetchStatus = 200;
  int saveStatus = 200;
  int fetchCount = 0;
  final patches = <Map<String, dynamic>>[];
  Completer<ResponseBody>? pendingFetch;
  Completer<ResponseBody>? pendingSave;
  Map<String, dynamic> profile = {
    'id': 'staff-1',
    'firstName': 'Anna',
    'lastName': 'Nowak',
    'phone': '123456789',
  };

  ResponseBody response(int status) => ResponseBody.fromString(
    jsonEncode(status == 200 ? profile : {'code': 'REQUEST_FAILED'}),
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
    // The trainer shell opens on the schedule tab.
    if (options.path == ownScheduleEventsPath) {
      return ResponseBody.fromString(
        jsonEncode({'items': []}),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    if (options.method == 'GET') {
      fetchCount++;
      if (pendingFetch != null) {
        return pendingFetch!.future;
      }
      return response(fetchStatus);
    }
    final patch = Map<String, dynamic>.from(options.data as Map);
    patches.add(patch);
    if (saveStatus == 200) {
      profile = {...profile, ...patch};
    }
    if (pendingSave != null) {
      return pendingSave!.future;
    }
    return response(saveStatus);
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

  Future<void> openEditor(WidgetTester tester, {bool settle = true}) async {
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
    // The account has several choices, so the role picker comes first.
    await tester.ensureVisible(find.text('Trener'));
    await tester.tap(find.text('Trener'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('Profil'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Dane osobowe'));
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
    }
  }

  Finder field(int index) => find.byType(TextFormField).at(index);

  Future<void> save(WidgetTester tester) async {
    await tester.ensureVisible(find.byType(FilledButton));
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'loads current values and saves without replacing customer data',
    (tester) async {
      await openEditor(tester);
      expect(find.text('Anna'), findsOneWidget);
      expect(find.text('Nowak'), findsOneWidget);
      expect(find.text('123456789'), findsOneWidget);

      await tester.enterText(field(0), ' Maria ');
      await tester.enterText(field(1), ' Kowalska ');
      await tester.enterText(field(2), '   ');
      await save(tester);

      expect(adapter.patches, [
        {'firstName': 'Maria', 'lastName': 'Kowalska', 'phone': null},
      ]);
      expect(find.byType(StaffProfileEditScreen), findsNothing);
      expect(find.text('MARIA KOWALSKA'), findsOneWidget);
      expect(find.text('Zmiany zostały zapisane.'), findsOneWidget);

      final context = tester.element(find.byType(NavigationBar));
      final container = ProviderScope.containerOf(context);
      final session =
          (container.read(authControllerProvider) as Authenticated).session;
      expect(session.activeRole, AppRole.trainer);
      expect(session.activeProfileId, 'child');
      expect(session.displayName, 'Client Name');
      expect(session.profiles.map((profile) => profile.displayName), [
        'Client Name',
        'Child Name',
      ]);

      await tester.tap(find.text('ZMIEŃ PROFIL'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Child'));
      await tester.pumpAndSettle();
      // Back on the profile tab, now as the child.
      expect(find.text('CHILD NAME'), findsOneWidget);
      expect(find.text('Dane osobowe'), findsNothing);
      final active = tester.widget<ChoiceTile>(
        find.descendant(
          of: find.byType(ProfileHeader),
          matching: find.byType(ChoiceTile),
        ),
      );
      expect(active.labels, (name: 'Child', role: 'Dziecko'));
    },
  );

  testWidgets(
    'validates trimmed required names and length limits before saving',
    (tester) async {
      await openEditor(tester);
      await tester.enterText(field(0), ' \t ');
      await tester.enterText(field(1), 'x' * 81);
      await tester.enterText(field(2), '1' * 21);
      await save(tester);

      expect(find.text('To pole jest wymagane.'), findsOneWidget);
      expect(find.text('Wpisz maksymalnie 80 znaków.'), findsOneWidget);
      expect(find.text('Wpisz maksymalnie 20 znaków.'), findsOneWidget);
      expect(adapter.patches, isEmpty);
    },
  );

  testWidgets('a failed save keeps typed values and can be retried', (
    tester,
  ) async {
    adapter.saveStatus = 500;
    await openEditor(tester);
    await tester.enterText(field(0), 'Maria');
    await tester.enterText(field(2), '+48 555 123 456');
    await save(tester);

    expect(find.byType(StaffProfileEditScreen), findsOneWidget);
    expect(find.text('Maria'), findsOneWidget);
    expect(find.text('+48 555 123 456'), findsOneWidget);
    expect(
      find.text('Nie udało się zapisać zmian. Spróbuj ponownie.'),
      findsOneWidget,
    );
    expect(find.text('Zmiany zostały zapisane.'), findsNothing);

    adapter.saveStatus = 200;
    await save(tester);
    expect(adapter.patches, hasLength(2));
    expect(find.text('MARIA NOWAK'), findsOneWidget);
    expect(find.text('Zmiany zostały zapisane.'), findsOneWidget);
  });

  testWidgets('a failed fetch offers retry and then populates the form', (
    tester,
  ) async {
    adapter.fetchStatus = 500;
    await openEditor(tester);
    expect(find.text('Nie udało się pobrać danych osobowych.'), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);

    adapter.fetchStatus = 200;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(adapter.fetchCount, 2);
    expect(find.text('Anna'), findsOneWidget);
    expect(find.byType(TextFormField), findsNWidgets(3));
  });

  testWidgets('shows loading until profile data arrives', (tester) async {
    adapter.pendingFetch = Completer<ResponseBody>();
    await openEditor(tester, settle: false);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);

    adapter.pendingFetch!.complete(adapter.response(200));
    await tester.pumpAndSettle();
    expect(find.text('Anna'), findsOneWidget);
  });

  testWidgets('blocks duplicate saves while the request is pending', (
    tester,
  ) async {
    adapter.pendingSave = Completer<ResponseBody>();
    await openEditor(tester);
    await tester.tap(find.byType(FilledButton));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(adapter.patches, hasLength(1));

    adapter.pendingSave!.complete(adapter.response(200));
    await tester.pumpAndSettle();
    expect(find.text('Zmiany zostały zapisane.'), findsOneWidget);
  });

  testWidgets(
    'a save completed after Back does not pop the profile or show success',
    (tester) async {
      adapter.pendingSave = Completer<ResponseBody>();
      await openEditor(tester);
      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));
      expect(adapter.patches, hasLength(1));

      await tester.tap(find.byType(BackButton));
      await tester.pump();
      adapter.pendingSave!.complete(adapter.response(200));
      await tester.pumpAndSettle();

      expect(find.byType(StaffProfileEditScreen), findsNothing);
      expect(find.text('Dane osobowe'), findsOneWidget);
      expect(find.text('ANNA NOWAK'), findsOneWidget);
      expect(find.text('Zmiany zostały zapisane.'), findsNothing);
    },
  );

  testWidgets('the form scrolls with a keyboard and large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 700);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await openEditor(tester);
    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    addTearDown(tester.view.resetViewInsets);
    await tester.showKeyboard(field(2));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(FilledButton).hitTestable(), findsOneWidget);
  });
}
