import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/trainings/data/class_interests_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/class_interest_switch.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class _Adapter implements HttpClientAdapter {
  int statusCode = 200;
  Object? body;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      body == null ? '' : jsonEncode(body),
      statusCode,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

class _InterestsApi implements ClassInterestsApi {
  _InterestsApi(this.marked);

  Set<String> marked;
  bool fail = false;
  final changes = <(String, String, bool)>[];

  @override
  Future<Set<String>> fetch(String profileId) async => marked;

  @override
  Future<Set<String>> change(
    String profileId,
    String serviceTypeId, {
    required bool interested,
  }) async {
    if (fail) throw Exception('offline');
    changes.add((profileId, serviceTypeId, interested));
    marked = interested
        ? {...marked, serviceTypeId}
        : ({...marked}..remove(serviceTypeId));
    return marked;
  }
}

const BookingScope _scope = (accountId: 'account-1', profileId: 'child-2');

void main() {
  group('ClassInterestsApi', () {
    late _Adapter adapter;
    late Dio dio;
    late ClassInterestsApi api;

    setUp(() {
      adapter = _Adapter();
      dio = Dio()..httpClientAdapter = adapter;
      api = ClassInterestsApi(dio);
    });

    tearDown(() => dio.close(force: true));

    test('reads, marks and unmarks the profile\'s service types', () async {
      adapter.body = {
        'serviceTypeIds': ['yoga', 'pilates'],
      };
      expect(await api.fetch('child-2'), {'yoga', 'pilates'});
      expect(
        adapter.requests.last.path,
        '/api/v1/me/customer-profiles/child-2/interests',
      );

      adapter.body = {
        'serviceTypeIds': ['yoga'],
      };
      expect(await api.change('child-2', 'yoga', interested: true), {'yoga'});
      expect(adapter.requests.last.method, 'PUT');
      expect(
        adapter.requests.last.path,
        '/api/v1/me/customer-profiles/child-2/interests/yoga',
      );

      adapter.body = {'serviceTypeIds': <String>[]};
      expect(await api.change('child-2', 'yoga', interested: false), isEmpty);
      expect(adapter.requests.last.method, 'DELETE');
    });
  });

  group('ClassInterestSwitch', () {
    Future<void> pump(WidgetTester tester, _InterestsApi api) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [classInterestsApiProvider.overrideWithValue(api)],
          child: MaterialApp(
            locale: const Locale('pl'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: const Scaffold(
              body: ClassInterestSwitch(
                scope: _scope,
                serviceTypeId: 'yoga',
                serviceTypeName: 'Joga',
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    bool switchedOn(WidgetTester tester) =>
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value;

    testWidgets('marks and unmarks the class\'s service type', (tester) async {
      final api = _InterestsApi({'pilates'});
      await pump(tester, api);

      expect(find.text('Powiadamiaj o zapisach: Joga'), findsOneWidget);
      expect(switchedOn(tester), isFalse);

      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(api.changes.last, ('child-2', 'yoga', true));
      expect(switchedOn(tester), isTrue);

      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(api.changes.last, ('child-2', 'yoga', false));
      expect(switchedOn(tester), isFalse);
    });

    testWidgets('keeps the choice and tells when saving fails', (tester) async {
      final api = _InterestsApi({'yoga'})..fail = true;
      await pump(tester, api);

      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();

      expect(switchedOn(tester), isTrue);
      expect(
        find.text(
          'Nie udało się zapisać ustawienia powiadomień. Spróbuj ponownie.',
        ),
        findsOneWidget,
      );
    });
  });
}
