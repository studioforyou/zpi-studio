import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/device_calendar.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/add_to_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class _FakeDeviceCalendar implements DeviceCalendar {
  final added = <DeviceCalendarEvent>[];
  bool opens = true;

  @override
  Future<bool> add(DeviceCalendarEvent event) async {
    added.add(event);
    return opens;
  }
}

final _startsAt = DateTime(2026, 12, 1, 18);

ScheduleEvent _event({EventStatus status = EventStatus.scheduled}) =>
    ScheduleEvent(
      id: 'event-1',
      kind: EventKind.groupClass,
      status: status,
      title: null,
      serviceTypeName: 'Pole dance',
      startsAt: _startsAt,
      endsAt: _startsAt.add(const Duration(hours: 1)),
      capacity: 8,
      staff: const [
        EventStaff(staffId: 's1', displayName: 'Anna Kowalska', isLead: true),
        EventStaff(staffId: 's2', displayName: 'Marek Nowak', isLead: false),
      ],
      roomNames: const ['Sala 1', 'Sala 2'],
    );

void main() {
  late _FakeDeviceCalendar calendar;

  setUp(() => calendar = _FakeDeviceCalendar());

  Future<void> pump(WidgetTester tester, Widget child) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [deviceCalendarProvider.overrideWithValue(calendar)],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: Scaffold(body: Center(child: child)),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('the details button adds the event with its rooms and leads', (
    tester,
  ) async {
    await pump(
      tester,
      AddToCalendarButton(event: _event(), participant: 'Zosia'),
    );

    await tester.tap(find.byKey(const Key('addToCalendarButton')));
    await tester.pumpAndSettle();

    final added = calendar.added.single;
    expect(added.title, 'Zosia: Pole dance');
    expect(added.startsAt, _startsAt);
    expect(added.endsAt, _startsAt.add(const Duration(hours: 1)));
    expect(added.location, 'Sala 1, Sala 2');
    expect(added.description, 'Prowadzi: Anna Kowalska');
  });

  testWidgets('holding an event down offers adding it', (tester) async {
    var tapped = 0;
    await pump(
      tester,
      AddToCalendarLongPress(
        event: _event(),
        child: ListTile(title: const Text('Pole dance'), onTap: () => tapped++),
      ),
    );

    await tester.tap(find.text('Pole dance'));
    await tester.pumpAndSettle();
    expect(tapped, 1);

    await tester.longPress(find.text('Pole dance'));
    await tester.pumpAndSettle();
    expect(find.text('Dodaj do kalendarza'), findsOneWidget);
    await tester.tap(find.byKey(const Key('addEventToCalendar')));
    await tester.pumpAndSettle();

    expect(calendar.added.single.title, 'Pole dance');
  });

  testWidgets('cancelled events are not offered', (tester) async {
    await pump(
      tester,
      Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AddToCalendarButton(event: _event(status: EventStatus.cancelled)),
          AddToCalendarLongPress(
            event: _event(status: EventStatus.cancelled),
            child: const Text('Odwołane'),
          ),
        ],
      ),
    );

    expect(find.byKey(const Key('addToCalendarButton')), findsNothing);
    await tester.longPress(find.text('Odwołane'));
    await tester.pumpAndSettle();
    expect(find.text('Dodaj do kalendarza'), findsNothing);
  });

  testWidgets('tells the user when the calendar app does not open', (
    tester,
  ) async {
    calendar.opens = false;
    await pump(tester, AddToCalendarButton(event: _event()));

    await tester.tap(find.byKey(const Key('addToCalendarButton')));
    await tester.pumpAndSettle();

    expect(
      find.text('Nie udało się otworzyć kalendarza w telefonie.'),
      findsOneWidget,
    );
  });
}
