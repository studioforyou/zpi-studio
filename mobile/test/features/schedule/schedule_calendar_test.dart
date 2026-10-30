import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

final _today = DateTime(2030, 11, 2);

ScheduleEvent _event(
  String id,
  DateTime startsAt, {
  EventStatus status = EventStatus.scheduled,
}) => ScheduleEvent(
  id: id,
  kind: EventKind.groupClass,
  status: status,
  title: id,
  serviceTypeName: null,
  startsAt: startsAt,
  endsAt: startsAt.add(const Duration(hours: 1)),
  capacity: 8,
  staff: const [],
  roomNames: const ['Sala 1'],
);

Future<void> _pump(WidgetTester tester, ScheduleCalendar calendar) async {
  tester.view.physicalSize = const Size(600, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: calendar),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'client rows and hidden legend work in day, week and month lists',
    (tester) async {
      final events = FutureProvider<List<ScheduleEvent>>(
        (ref) => [_event('Pole dance', DateTime(2030, 11, 2, 17))],
      );
      await _pump(
        tester,
        ScheduleCalendar(
          eventsFor: (_) => events,
          today: _today,
          showLegend: false,
          eventBuilder: (_, event) => Text('Rezerwacja: ${event.name}'),
        ),
      );
      expect(find.text('Rezerwacja: Pole dance'), findsOneWidget);
      expect(find.byType(ScheduleEventTile), findsNothing);
      expect(find.text('Trening personalny'), findsNothing);

      await tester.tap(find.text('Dzień'));
      await tester.pumpAndSettle();
      expect(find.text('Rezerwacja: Pole dance'), findsOneWidget);

      await tester.tap(find.text('Miesiąc'));
      await tester.pumpAndSettle();
      expect(find.text('Rezerwacja: Pole dance'), findsOneWidget);
      expect(find.text('Trening personalny'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'custom day gets only filtered overlapping events and supports refresh',
    (tester) async {
      var reads = 0;
      final events = FutureProvider<List<ScheduleEvent>>((ref) {
        reads++;
        return [
          _event('Dzisiaj', DateTime(2030, 11, 2, 17)),
          _event('Ukryte', DateTime(2030, 11, 2, 18)),
          _event('Jutro', DateTime(2030, 11, 3, 17)),
        ];
      });
      List<ScheduleEvent>? dayEvents;
      DateTime? day;
      await _pump(
        tester,
        ScheduleCalendar(
          eventsFor: (_) => events,
          today: _today,
          where: (event) => event.id != 'Ukryte',
          showLegend: false,
          dayBuilder: (_, items, selected) {
            dayEvents = items;
            day = selected;
            return ListView(
              key: const ValueKey('custom-day'),
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                for (final event in items) Text('Plan: ${event.name}'),
              ],
            );
          },
        ),
      );
      await tester.tap(find.text('Dzień'));
      await tester.pumpAndSettle();
      expect(day, _today);
      expect(dayEvents!.map((event) => event.id), ['Dzisiaj']);
      expect(find.text('Plan: Dzisiaj'), findsOneWidget);
      expect(find.textContaining('Jutro'), findsNothing);

      await tester.drag(
        find.byKey(const ValueKey('custom-day')),
        const Offset(0, 600),
      );
      await tester.pumpAndSettle();
      expect(reads, 2);
      expect(find.text('Plan: Dzisiaj'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'month markers use service colours and keep cancelled events red',
    (tester) async {
      const serviceColor = Color(0xFF12695A);
      final events = FutureProvider<List<ScheduleEvent>>(
        (ref) => [
          _event('Pole dance', DateTime(2030, 11, 2, 17)),
          _event(
            'Odwołany trening',
            DateTime(2030, 11, 3, 17),
            status: EventStatus.cancelled,
          ),
        ],
      );
      await _pump(
        tester,
        ScheduleCalendar(
          eventsFor: (_) => events,
          today: _today,
          eventColor: (_, _) => serviceColor,
          legendBuilder: (_, _) => const Text('Kolory rodzajów zajęć'),
        ),
      );
      await tester.tap(find.text('Miesiąc'));
      await tester.pumpAndSettle();
      final scheme = Theme.of(
        tester.element(find.byType(ScheduleCalendar)),
      ).colorScheme;
      final markerColors = tester
          .widgetList<Container>(find.byType(Container))
          .map((container) => container.decoration)
          .whereType<BoxDecoration>()
          .where((decoration) => decoration.shape == BoxShape.circle)
          .map((decoration) => decoration.color);
      expect(markerColors, contains(serviceColor));
      expect(markerColors, contains(scheme.error));
      expect(find.text('Kolory rodzajów zajęć'), findsOneWidget);
      expect(find.text('Trening personalny'), findsNothing);
    },
  );

  testWidgets(
    'calendar and dense month markers fit a phone with enlarged text',
    (tester) async {
      final events = FutureProvider<List<ScheduleEvent>>(
        (ref) => [
          for (var i = 0; i < 20; i++)
            _event('Zajęcia $i', DateTime(2030, 11, 2, 17)),
          for (var i = 0; i < 3; i++)
            _event('Trzy $i', DateTime(2030, 11, 3, 17)),
          for (var i = 0; i < 4; i++)
            _event('Cztery $i', DateTime(2030, 11, 4, 17)),
        ],
      );
      await _pump(
        tester,
        ScheduleCalendar(eventsFor: (_) => events, today: _today),
      );
      tester.view.physicalSize = const Size(360, 740);
      tester.platformDispatcher.textScaleFactorTestValue = 1.5;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpAndSettle();
      for (final view in ['Dzień', 'Miesiąc', 'Tydzień']) {
        await tester.tap(find.text(view));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull, reason: view);
        if (view == 'Miesiąc') {
          expect(find.text('+17'), findsOneWidget);
          expect(find.text('+1'), findsOneWidget);
          expect(find.text('+'), findsNothing);
          expect(find.text('+0'), findsNothing);
          expect(find.byTooltip('Łącznie 20 zajęć'), findsOneWidget);
          expect(find.byTooltip('Łącznie 3 zajęcia'), findsOneWidget);
          expect(find.byTooltip('Łącznie 4 zajęcia'), findsOneWidget);
        }
      }
    },
  );

  testWidgets(
    'custom refresh reloads a cached source for retry and pull refresh',
    (tester) async {
      var fails = true;
      var title = 'Pierwszy zapis';
      var rawReads = 0;
      final raw = FutureProvider<List<ScheduleEvent>>((ref) {
        rawReads++;
        if (fails) throw StateError('Offline');
        return [_event(title, DateTime(2030, 11, 2, 17))];
      });
      final adapted = FutureProvider<List<ScheduleEvent>>(
        (ref) => ref.watch(raw.future),
      );
      final windows = <ScheduleWindow>[];
      late ProviderContainer container;
      await _pump(
        tester,
        ScheduleCalendar(
          eventsFor: (_) => adapted,
          today: _today,
          showLegend: false,
          onRefresh: (window) async {
            windows.add(window);
            container.invalidate(raw);
            final request = container.refresh(adapted.future);
            await request;
          },
        ),
      );
      container = ProviderScope.containerOf(
        tester.element(find.byType(ScheduleCalendar)),
        listen: false,
      );
      expect(rawReads, 1);
      expect(find.text('Spróbuj ponownie'), findsOneWidget);

      fails = false;
      await tester.tap(find.text('Spróbuj ponownie'));
      await tester.pumpAndSettle();
      expect(rawReads, 2);
      expect(find.text('Pierwszy zapis'), findsOneWidget);
      expect(windows.single, (
        from: DateTime(2030, 10, 28),
        to: DateTime(2030, 11, 4),
      ));

      title = 'Odświeżony zapis';
      await tester.drag(find.byType(ListView), const Offset(0, 600));
      await tester.pumpAndSettle();
      expect(rawReads, 3);
      expect(windows, hasLength(2));
      expect(find.text('Odświeżony zapis'), findsOneWidget);
      expect(find.text('Pierwszy zapis'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
