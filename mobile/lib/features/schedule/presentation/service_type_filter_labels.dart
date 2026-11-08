import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Known level codes from the easiest; unknown codes sort after them.
const _levelOrder = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'ALL_LEVELS'];

/// Name of a service level; an unknown code is shown as it is.
String serviceLevelLabel(AppLocalizations l10n, String level) =>
    switch (level) {
      'BEGINNER' => l10n.serviceLevelBeginner,
      'INTERMEDIATE' => l10n.serviceLevelIntermediate,
      'ADVANCED' => l10n.serviceLevelAdvanced,
      'ALL_LEVELS' => l10n.serviceLevelAll,
      _ => level,
    };

String ageGroupLabel(AppLocalizations l10n, AgeGroup group) => switch (group) {
  AgeGroup.children => l10n.scheduleAgeGroupChildren,
  AgeGroup.adults => l10n.scheduleAgeGroupAdults,
};

/// [levels] from the easiest to the most advanced.
List<String> sortedLevels(Iterable<String> levels) {
  int rank(String level) {
    final index = _levelOrder.indexOf(level);
    return index < 0 ? _levelOrder.length : index;
  }

  return levels.toSet().toList()..sort((a, b) {
    final byRank = rank(a).compareTo(rank(b));
    return byRank != 0 ? byRank : a.compareTo(b);
  });
}

/// Levels and age groups of the studio's whole service catalogue, like in
/// the web schedule, so the choices do not disappear in a period without
/// events. The selected ones are kept, so a selection can always be turned
/// off.
({List<String> levels, List<AgeGroup> ageGroups}) serviceTypeFilterOptions(
  Map<String, ScheduleServiceType> serviceTypes, {
  Set<String> selectedLevels = const {},
  Set<AgeGroup> selectedAgeGroups = const {},
}) {
  final types = serviceTypes.values;
  final ageGroups = {
    ...selectedAgeGroups,
    for (final type in types) type.ageGroup,
  };
  return (
    levels: sortedLevels({
      ...selectedLevels,
      for (final type in types)
        if (type.level case final String level) level,
    }),
    ageGroups: [
      for (final group in AgeGroup.values)
        if (ageGroups.contains(group)) group,
    ],
  );
}

/// Whether an event of [serviceType] passes the level and age group
/// criteria. Empty sets do not filter. An event without a known service
/// does not pass a criterion that is set.
bool matchesServiceType(
  ScheduleServiceType? serviceType, {
  required Set<String> levels,
  required Set<AgeGroup> ageGroups,
}) {
  if (levels.isNotEmpty &&
      (serviceType?.level == null || !levels.contains(serviceType!.level))) {
    return false;
  }
  if (ageGroups.isNotEmpty &&
      (serviceType == null || !ageGroups.contains(serviceType.ageGroup))) {
    return false;
  }
  return true;
}
