import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/presentation/service_type_filter_labels.dart';

ScheduleServiceType _type(String id, {String? level, int? maxAge}) =>
    ScheduleServiceType(id: id, level: level, minAge: null, maxAge: maxAge);

void main() {
  test('a service with an upper age limit below 18 is for children', () {
    expect(_type('a', maxAge: 12).ageGroup, AgeGroup.children);
    expect(_type('b', maxAge: 17).ageGroup, AgeGroup.children);
    expect(_type('c', maxAge: 18).ageGroup, AgeGroup.adults);
    expect(_type('d').ageGroup, AgeGroup.adults);
  });

  test('parses a service type from the catalogue', () {
    final type = ScheduleServiceType.fromJson({
      'id': 'service-1',
      'name': 'Pole dance',
      'level': 'BEGINNER',
      'minAge': 16,
      'maxAge': null,
    });
    expect(type.id, 'service-1');
    expect(type.level, 'BEGINNER');
    expect(type.minAge, 16);
    expect(type.maxAge, isNull);
  });

  test('empty criteria let every event through', () {
    expect(matchesServiceType(null, levels: {}, ageGroups: {}), isTrue);
  });

  test('a set criterion needs a known service that meets it', () {
    final beginner = _type('a', level: 'BEGINNER');
    final kids = _type('b', maxAge: 10);

    expect(
      matchesServiceType(beginner, levels: {'BEGINNER'}, ageGroups: {}),
      isTrue,
    );
    expect(
      matchesServiceType(kids, levels: {'BEGINNER'}, ageGroups: {}),
      isFalse,
    );
    expect(
      matchesServiceType(null, levels: {'BEGINNER'}, ageGroups: {}),
      isFalse,
    );
    expect(
      matchesServiceType(kids, levels: {}, ageGroups: {AgeGroup.children}),
      isTrue,
    );
    expect(
      matchesServiceType(null, levels: {}, ageGroups: {AgeGroup.adults}),
      isFalse,
    );
  });

  test('options come from the whole catalogue and keep the selected ones', () {
    final types = {
      'a': _type('a', level: 'ADVANCED'),
      'b': _type('b', level: 'BEGINNER', maxAge: 12),
      'c': _type('c', level: 'CUSTOM'),
    };

    final options = serviceTypeFilterOptions(types);
    expect(options.levels, ['BEGINNER', 'ADVANCED', 'CUSTOM']);
    expect(options.ageGroups, [AgeGroup.children, AgeGroup.adults]);

    final kept = serviceTypeFilterOptions(
      {'a': types['a']!},
      selectedLevels: {'INTERMEDIATE'},
      selectedAgeGroups: {AgeGroup.children},
    );
    expect(kept.levels, ['INTERMEDIATE', 'ADVANCED']);
    expect(kept.ageGroups, [AgeGroup.children, AgeGroup.adults]);

    final none = serviceTypeFilterOptions(const {});
    expect(none.levels, isEmpty);
    expect(none.ageGroups, isEmpty);
  });
}
