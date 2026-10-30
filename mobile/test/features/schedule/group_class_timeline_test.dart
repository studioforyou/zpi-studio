import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/group_class_timeline.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

import '../trainings/group_class_fixtures.dart';

GroupClass _event(String id, int hour, int minute, int duration) {
  final start = DateTime(2030, 11, 2, hour, minute);
  return GroupClass.fromJson({
    ...groupClassJson(availablePlaces: 0),
    'id': id,
    'title': id,
    'startsAt': start.toIso8601String(),
    'endsAt': start.add(Duration(minutes: duration)).toIso8601String(),
  });
}

void main() {
  test('overlaps get separate columns and later classes use full width', () {
    final placed = placeGroupClasses([
      _event('A', 9, 0, 60),
      _event('B', 9, 30, 60),
      _event('C', 11, 0, 60),
    ]);
    expect(placed[0].laneCount, 2);
    expect(placed[1].laneCount, 2);
    expect(placed[0].lane, isNot(placed[1].lane));
    expect(placed[0].startMinute, 540);
    expect(placed[1].startMinute, 570);
    expect(placed[2].laneCount, 1);
  });

  test(
    'connected overlaps reuse a free column without covering another class',
    () {
      final placed = placeGroupClasses([
        _event('A', 9, 0, 60),
        _event('B', 9, 30, 90),
        _event('C', 10, 0, 60),
      ]);
      expect(placed.map((p) => p.laneCount), [2, 2, 2]);
      expect(placed[2].lane, placed[0].lane);
    },
  );

  test(
    'short consecutive classes retain their real duration in one column',
    () {
      final placed = placeGroupClasses([
        _event('A', 9, 0, 15),
        _event('B', 9, 15, 15),
        _event('C', 9, 30, 30),
      ]);
      expect(placed.map((p) => p.laneCount), [1, 1, 1]);
      expect(placed[0].endMinute, placed[1].startMinute);
      expect(placed[1].endMinute, placed[2].startMinute);
      expect(placed[0].endMinute - placed[0].startMinute, 15);
    },
  );

  test(
    'overnight class clips at midnight on both days and is absent the day after',
    () {
      final overnight = _event('Overnight', 23, 30, 60);
      final [firstDay] = placeGroupClasses([
        overnight,
      ], day: DateTime(2030, 11, 2));
      final [secondDay] = placeGroupClasses([
        overnight,
      ], day: DateTime(2030, 11, 3));
      expect(firstDay.startMinute, 1410);
      expect(firstDay.endMinute, 1440);
      expect(secondDay.startMinute, 0);
      expect(secondDay.endMinute, 30);
      expect(
        placeGroupClasses([overnight], day: DateTime(2030, 11, 4)),
        isEmpty,
      );
    },
  );

  testWidgets(
    'three short simultaneous classes fit a phone at text scale 1.5',
    (tester) async {
      tester.view.physicalSize = const Size(360, 740);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.5;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final events = [
        _event('Pole dance', 9, 0, 30),
        _event('Stretching', 9, 0, 30),
        _event('Zdrowy kręgosłup', 9, 0, 30),
      ];
      GroupClass? selected;
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: GroupClassTimeline(
              events: events,
              day: DateTime(2030, 11, 2),
              onSelect: (event) => selected = event,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.textContaining('8/8'), findsNWidgets(3));
      final positions = [
        for (final name in ['Pole dance', 'Stretching', 'Zdrowy kręgosłup'])
          tester.getTopLeft(find.text(name)),
      ];
      expect(positions[0].dy, positions[1].dy);
      expect(positions[1].dy, positions[2].dy);
      expect(positions[0].dx, lessThan(positions[1].dx));
      expect(positions[1].dx, lessThan(positions[2].dx));
      expect(positions[1].dx - positions[0].dx, greaterThanOrEqualTo(140));
      final hourPosition = tester.getTopLeft(find.text('09:00'));
      await tester.drag(
        find.byType(SingleChildScrollView).last,
        const Offset(-240, 0),
      );
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('09:00')), hourPosition);
      await tester.tap(find.text('Zdrowy kręgosłup'));
      expect(selected?.id, 'Zdrowy kręgosłup');
    },
  );
}
