import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

import 'fake_group_classes_api.dart';
import 'group_class_fixtures.dart';

const _scope = (accountId: 'parent', profileId: 'self');
const _session = Session(
  accountId: 'parent',
  displayName: 'Anna Klient',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Klient', isChild: false),
    Profile(id: 'child', displayName: 'Jan Klient', isChild: true),
  ],
  activeProfileId: 'self',
);

final _child = SessionChoice(
  role: AppRole.client,
  profile: _session.profiles[1],
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  /// Opens the class sheet of `class-1` over an empty screen.
  Future<ProviderContainer> pump(
    WidgetTester tester,
    FakeGroupClassesApi api, {
    bool phoneSize = false,
  }) async {
    if (!phoneSize) {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    final container = ProviderContainer(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        groupClassesApiProvider.overrideWithValue(api),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: buildClientTheme(Brightness.light),
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => showGroupClassSheet(context, (
                    scope: _scope,
                    eventId: 'class-1',
                  )),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return container;
  }

  Finder button(String label) => find.widgetWithText(AppButton, label);
  VoidCallback? onPressed(WidgetTester tester, Finder finder) =>
      tester.widget<AppButton>(finder.last).onPressed;

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder.last);
    await tester.pumpAndSettle();
    await tester.tap(finder.last);
    await tester.pumpAndSettle();
  }

  group('booking', () {
    testWidgets('shows the class and books it with the only pass', (
      tester,
    ) async {
      final api = FakeGroupClassesApi();
      await pump(tester, api);
      expect(find.text('Pole dance'), findsOneWidget);
      expect(find.text('Anna Trener'), findsOneWidget);
      expect(find.text('Sala Turkusowa'), findsOneWidget);
      expect(find.text('Karnet 1 · zostaną 3 wejścia'), findsOneWidget);
      // One pass leaves nothing to change.
      expect(find.text('Zmień'), findsNothing);

      await tap(tester, button('Zapisz się'));
      expect(api.bookedPassId, 'pass-1');
      expect(api.bookedQuery?.scope, _scope);
      expect(find.text('Masz rezerwację'), findsOneWidget);
      expect(button('Wypisz się'), findsOneWidget);
    });

    testWidgets('takes the pass that ends first until another is chosen', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: {
          ...groupClassJson(availablePlaces: null),
          'booking': {
            'allowed': true,
            'reason': null,
            'eligiblePasses': [
              {
                'id': 'pass-late',
                'productName': 'Open',
                'entriesRemaining': null,
                'validUntil': '2030-12-30',
              },
              {
                'id': 'pass-soon',
                'productName': 'Pole Dance 8',
                'entriesRemaining': 4,
                'validUntil': '2030-11-30',
              },
            ],
          },
        },
      );
      await pump(tester, api);
      expect(find.text('Bez limitu miejsc'), findsOneWidget);
      expect(find.text('Pole Dance 8 · zostaną 3 wejścia'), findsOneWidget);

      await tap(tester, find.text('Zmień'));
      expect(find.byType(ChoiceOptions<String>), findsOneWidget);
      await tap(tester, find.text('Open'));
      expect(find.byType(ChoiceOptions<String>), findsNothing);
      expect(find.text('Open · bez limitu wejść'), findsOneWidget);

      await tap(tester, button('Zapisz się'));
      expect(api.bookedPassId, 'pass-late');
    });

    testWidgets('explains unavailable booking and disables it', (tester) async {
      await pump(
        tester,
        FakeGroupClassesApi(
          data: groupClassJson(
            allowed: false,
            reason: 'REQUIRED_DOCUMENTS_MISSING',
            passCount: 0,
          ),
        ),
      );
      expect(
        find.text(
          'Przed zapisem zaakceptuj wymagane dokumenty w sekcji Dokumenty i zgody.',
        ),
        findsOneWidget,
      );
      expect(button('Przejdź do dokumentów'), findsOneWidget);
      expect(onPressed(tester, button('Zapisz się')), isNull);
    });

    testWidgets('a profile changed while the sheet is open stops booking', (
      tester,
    ) async {
      final api = FakeGroupClassesApi();
      final container = await pump(tester, api);
      container.read(authControllerProvider.notifier).choose(_child);
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Zmieniono profil uczestnika'),
        findsOneWidget,
      );
      expect(button('Zapisz się'), findsNothing);
      expect(api.bookedPassId, isNull);
    });
  });

  group('waiting list', () {
    testWidgets('a full class joins the list with the chosen pass', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: fullClassJson(waitingCount: 2, passCount: 2),
      );
      await pump(tester, api);
      expect(
        find.text('Na liście rezerwowej czekają już 2 osoby.'),
        findsOneWidget,
      );
      expect(button('Zapisz się'), findsNothing);

      await tap(tester, find.text('Zmień'));
      await tap(tester, find.text('Karnet 2'));
      await tap(tester, button('Dołącz do listy rezerwowej'));

      expect(api.joinedPassId, 'pass-2');
      expect(api.joinedQuery, (scope: _scope, eventId: 'class-1'));
      expect(api.bookedPassId, isNull);
      expect(find.text('Jesteś na liście rezerwowej'), findsOneWidget);
      expect(find.text('Pozycja na liście: 3 z 3'), findsOneWidget);
      expect(
        find.text(
          'Gdy zwolni się miejsce, zapiszemy Cię automatycznie — '
          'zajęcia pojawią się w widoku Moje.',
        ),
        findsOneWidget,
      );
      expect(onPressed(tester, button('Opuść listę rezerwową')), isNotNull);
    });

    testWidgets('the participant leaves the list only after confirming', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: fullClassJson(waitlistPosition: 2, waitingCount: 3),
      );
      await pump(tester, api);
      expect(find.text('Pozycja na liście: 2 z 3'), findsOneWidget);

      await tap(tester, button('Opuść listę rezerwową'));
      expect(find.text('Opuścić listę rezerwową?'), findsOneWidget);
      expect(find.text('Stracisz swoje miejsce w kolejce.'), findsOneWidget);
      await tap(tester, button('Zostań na liście'));
      expect(api.leftEntryId, isNull);

      await tap(tester, button('Opuść listę rezerwową'));
      await tap(tester, button('Opuść listę'));
      expect(api.leftEntryId, 'waitlist-1');
      expect(api.leftScope, _scope);
      expect(find.text('Jesteś na liście rezerwowej'), findsNothing);
      expect(
        onPressed(tester, button('Dołącz do listy rezerwowej')),
        isNotNull,
      );
    });

    for (final (name, data) in [
      (
        'a disabled',
        fullClassJson(
          waitlistAllowed: false,
          waitlistReason: 'WAITLIST_DISABLED',
        ),
      ),
      ('no', fullClassJson()..remove('waitlist')),
    ]) {
      testWidgets('a full class with $name waiting list cannot be booked', (
        tester,
      ) async {
        await pump(tester, FakeGroupClassesApi(data: data));
        expect(onPressed(tester, button('Zapisz się')), isNull);
        expect(find.text('Dołącz do listy rezerwowej'), findsNothing);
      });
    }

    for (final (reason, passCount, text) in [
      (
        'NO_ELIGIBLE_PASS',
        0,
        'Wybrany uczestnik nie ma karnetu ważnego na te zajęcia, '
            'z dostępnym wejściem i odpowiednim zakresem usług.',
      ),
      (
        'REQUIRED_DOCUMENTS_MISSING',
        1,
        'Przed zapisem zaakceptuj wymagane dokumenty w sekcji '
            'Dokumenty i zgody.',
      ),
    ]) {
      testWidgets('a full class explains why joining is refused: $reason', (
        tester,
      ) async {
        await pump(
          tester,
          FakeGroupClassesApi(
            data: fullClassJson(
              waitlistAllowed: false,
              waitlistReason: reason,
              waitingCount: 2,
              passCount: passCount,
            ),
          ),
        );
        expect(find.text(text), findsOneWidget);
        expect(
          find.textContaining('zapiszemy Cię automatycznie'),
          findsNothing,
        );
        expect(
          button('Przejdź do dokumentów'),
          reason == 'REQUIRED_DOCUMENTS_MISSING'
              ? findsOneWidget
              : findsNothing,
        );
        expect(onPressed(tester, button('Dołącz do listy rezerwowej')), isNull);
      });
    }

    for (final (reason, text) in [
      (
        'REQUIRED_DOCUMENTS_MISSING',
        'Na razie nie zapiszemy Cię automatycznie. Przed zapisem zaakceptuj '
            'wymagane dokumenty w sekcji Dokumenty i zgody.',
      ),
      (
        'BOOKING_CLOSED',
        'Lista rezerwowa jest już zamknięta — nie przydzielimy z niej miejsca '
            'na te zajęcia.',
      ),
    ]) {
      testWidgets('a waiting participant sees a blocked promotion: $reason', (
        tester,
      ) async {
        await pump(
          tester,
          FakeGroupClassesApi(
            data: fullClassJson(
              waitlistPosition: 1,
              promotionBlockedReason: reason,
            ),
          ),
        );
        expect(find.text('Pozycja na liście: 1 z 1'), findsOneWidget);
        expect(find.text(text), findsOneWidget);
        expect(onPressed(tester, button('Opuść listę rezerwową')), isNotNull);
      });
    }

    testWidgets('a waiting participant books a place the queue left free', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: groupClassJson(availablePlaces: 1, waitlistPosition: 1),
      );
      await pump(tester, api);
      expect(
        find.text('Zwolniło się miejsce — możesz zapisać się na zajęcia.'),
        findsOneWidget,
      );
      await tap(tester, button('Zapisz się'));
      expect(api.bookedPassId, 'pass-1');
      expect(api.joinedPassId, isNull);
      expect(find.text('Masz rezerwację'), findsOneWidget);
      expect(button('Opuść listę rezerwową'), findsNothing);
    });

    testWidgets('a place freed while joining is offered for booking', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(data: fullClassJson())
        ..placeFreedBeforeJoin = true;
      await pump(tester, api);
      final reads = api.classReads;
      await tap(tester, button('Dołącz do listy rezerwowej'));
      expect(api.joinedPassId, 'pass-1');
      expect(api.classReads, greaterThan(reads));
      expect(button('Dołącz do listy rezerwowej'), findsNothing);
      expect(onPressed(tester, button('Zapisz się')), isNotNull);
    });

    testWidgets('joining with a confirmation time says the place is held', (
      tester,
    ) async {
      await pump(
        tester,
        FakeGroupClassesApi(data: withConfirmation(fullClassJson())),
      );
      expect(
        find.textContaining('zarezerwujemy je dla Ciebie na 30 min'),
        findsOneWidget,
      );
    });

    testWidgets('waiting with a confirmation time explains it', (tester) async {
      await pump(
        tester,
        FakeGroupClassesApi(
          data: withConfirmation(fullClassJson(waitlistPosition: 2)),
        ),
      );
      expect(
        find.textContaining('potwierdź je wtedy na tym ekranie'),
        findsOneWidget,
      );
    });
  });

  group('a freed place', () {
    testWidgets('is confirmed before its deadline', (tester) async {
      final api = FakeGroupClassesApi(data: offeredClassJson());
      await pump(tester, api);
      expect(
        find.textContaining('Zwolniło się miejsce dla Ciebie'),
        findsOneWidget,
      );
      expect(find.text('Miejsce czeka na Ciebie'), findsOneWidget);
      expect(find.textContaining('Pozycja na liście'), findsNothing);
      await tap(tester, button('Potwierdź miejsce'));
      expect(api.confirmedEntryId, 'waitlist-1');
      expect(find.text('Masz rezerwację'), findsOneWidget);
    });

    testWidgets('declined passes to the next person', (tester) async {
      final api = FakeGroupClassesApi(data: offeredClassJson());
      await pump(tester, api);
      await tap(tester, button('Zrezygnuj z miejsca'));
      expect(
        find.text('Miejsce przejdzie na kolejną osobę z listy rezerwowej.'),
        findsOneWidget,
      );
      await tap(tester, button('Zrezygnuj z miejsca'));
      expect(api.leftEntryId, 'waitlist-1');
      expect(api.confirmedEntryId, isNull);
    });

    testWidgets('cannot be confirmed after the deadline', (tester) async {
      await pump(
        tester,
        FakeGroupClassesApi(
          data: offeredClassJson(offerExpiresAt: '2020-01-01T00:00:00Z'),
        ),
      );
      expect(
        find.text(
          'Czas na potwierdzenie miejsca minął — przeszło ono na kolejną '
          'osobę z listy.',
        ),
        findsOneWidget,
      );
      expect(onPressed(tester, button('Potwierdź miejsce')), isNull);
    });

    testWidgets('blocked by missing documents points to them', (tester) async {
      await pump(
        tester,
        FakeGroupClassesApi(
          data: offeredClassJson(
            promotionBlockedReason: 'REQUIRED_DOCUMENTS_MISSING',
          ),
        ),
      );
      expect(
        find.textContaining('Teraz nie możemy Cię zapisać.'),
        findsOneWidget,
      );
      expect(button('Przejdź do dokumentów'), findsOneWidget);
      expect(onPressed(tester, button('Potwierdź miejsce')), isNull);
    });
  });

  group('a reservation', () {
    testWidgets('cancelling on time lists the pass and its balance', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: groupClassJson(
          reserved: true,
          passConsequence: {
            'source': 'PASS',
            'productName': 'Open 8 wejść',
            'entriesRemaining': 5,
            'entriesAfterCancellation': 6,
            'validUntil': '2030-11-30',
            'expiresAt': null,
          },
        ),
      );
      await pump(tester, api);
      expect(find.text('Open 8 wejść'), findsOneWidget);
      await tap(tester, button('Wypisz się'));
      expect(find.text('Odwołać rezerwację?'), findsOneWidget);
      expect(find.byType(ConsequenceList), findsOneWidget);
      expect(find.textContaining('Pozostało wejść: 5'), findsOneWidget);
      expect(api.cancellations, 0);

      await tap(tester, button('Wypisz się'));
      expect(api.cancellations, 1);
      expect(api.expectedEntryReturned, isTrue);
      expect(button('Zapisz się'), findsOneWidget);
    });

    testWidgets('a changed deadline asks for a new confirmation', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(data: groupClassJson(reserved: true))
        ..cancellationConflict = true
        ..cancellationReturned = false;
      await pump(tester, api);
      await tap(tester, button('Wypisz się'));
      await tap(tester, button('Wypisz się'));
      expect(api.cancellations, 1);
      expect(
        find.textContaining('Warunki rezerwacji lub odwołania zmieniły się'),
        findsOneWidget,
      );

      await tap(tester, button('Wypisz się'));
      expect(find.textContaining('Odwołanie po terminie'), findsWidgets);
      await tap(tester, button('Wypisz się'));
      expect(api.cancellations, 2);
      expect(api.expectedEntryReturned, isFalse);
    });

    testWidgets('a place booked without a pass costs nothing to cancel', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(
        data: groupClassJson(reserved: true, reservedWithPass: false),
      );
      await pump(tester, api);
      expect(
        find.text('Studio zapisało to miejsce bez karnetu.'),
        findsOneWidget,
      );
      expect(find.textContaining('Bezpłatne odwołanie'), findsNothing);
      await tap(tester, button('Wypisz się'));
      expect(
        find.text(
          'Rezerwacja jest bez karnetu, więc odwołanie nie zmienia wejść.',
        ),
        findsOneWidget,
      );
      await tap(tester, button('Wypisz się'));
      expect(api.cancellations, 1);
      expect(api.expectedEntryReturned, isNull);
    });

    testWidgets('shows who else comes and retries a failed list', (
      tester,
    ) async {
      final api = FakeGroupClassesApi(data: groupClassJson(reserved: true))
        ..participantsFail = true;
      await pump(tester, api);
      expect(
        find.text('Nie udało się pobrać listy uczestników.'),
        findsOneWidget,
      );
      api.participantsFail = false;
      await tap(tester, button('Spróbuj ponownie'));
      expect(find.text('Maria Nowak'), findsOneWidget);
      expect(find.text('Anna Klient (wybrany profil)'), findsOneWidget);
      expect(find.text('Uczestnicy · 2'), findsOneWidget);
    });

    testWidgets('names of participants stay hidden before booking', (
      tester,
    ) async {
      final api = FakeGroupClassesApi();
      await pump(tester, api);
      expect(find.textContaining('Uczestnicy'), findsNothing);
      expect(api.participantReads, 0);
    });
  });

  testWidgets('a class cancelled by the studio offers another one', (
    tester,
  ) async {
    final api = FakeGroupClassesApi(
      data: {
        ...groupClassJson(reserved: true),
        'status': 'CANCELLED',
        'ownReservation': reservationJson(active: false),
      },
    );
    await pump(tester, api);
    expect(
      find.text('Zajęcia odwołane przez studio. Wejście wróciło na karnet.'),
      findsOneWidget,
    );
    await tap(tester, button('Wybierz nowe zajęcia'));
    expect(find.text('Pole dance'), findsNothing);
  });

  testWidgets('the sheet fits a phone with enlarged text', (tester) async {
    tester.view.physicalSize = const Size(360, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.reset);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await pump(
      tester,
      FakeGroupClassesApi(data: fullClassJson(waitingCount: 12, passCount: 2)),
      phoneSize: true,
    );
    expect(tester.takeException(), isNull);
    expect(button('Dołącz do listy rezerwowej').hitTestable(), findsOneWidget);
  });
}
