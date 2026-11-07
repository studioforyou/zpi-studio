import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/presentation/service_type_filter_labels.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

class ClientScheduleFilter {
  const ClientScheduleFilter({
    this.services = const {},
    this.instructors = const {},
    this.levels = const {},
    this.ageGroups = const {},
    this.availableOnly = false,
    this.showCancelled = true,
  });

  final Set<String> services;
  final Set<String> instructors;

  /// Service level codes, e.g. `BEGINNER`; any level when empty.
  final Set<String> levels;

  /// Age groups of the service; any age group when empty.
  final Set<AgeGroup> ageGroups;
  final bool availableOnly;
  final bool showCancelled;

  int get activeCount =>
      (services.isEmpty ? 0 : 1) +
      (instructors.isEmpty ? 0 : 1) +
      (levels.isEmpty ? 0 : 1) +
      (ageGroups.isEmpty ? 0 : 1) +
      (availableOnly ? 1 : 0) +
      (showCancelled ? 0 : 1);

  static String serviceKey(GroupClass event) =>
      event.serviceTypeId ?? event.serviceTypeName ?? event.name ?? '';

  /// Whether [event] passes the filter. [serviceTypes] gives the level and
  /// age limits of the event's service.
  bool matches(
    GroupClass event, [
    Map<String, ScheduleServiceType> serviceTypes = const {},
  ]) =>
      (services.isEmpty || services.contains(serviceKey(event))) &&
      (instructors.isEmpty ||
          event.staff.any(
            (person) => person.isLead && instructors.contains(person.staffId),
          )) &&
      matchesServiceType(
        serviceTypes[event.serviceTypeId],
        levels: levels,
        ageGroups: ageGroups,
      ) &&
      (showCancelled || event.status != 'CANCELLED') &&
      (!availableOnly ||
          (event.status == 'SCHEDULED' &&
              event.startsAt.isAfter(DateTime.now()) &&
              !event.isFull));

  ClientScheduleFilter copyWith({
    Set<String>? services,
    Set<String>? instructors,
    Set<String>? levels,
    Set<AgeGroup>? ageGroups,
    bool? availableOnly,
    bool? showCancelled,
  }) => ClientScheduleFilter(
    services: services ?? this.services,
    instructors: instructors ?? this.instructors,
    levels: levels ?? this.levels,
    ageGroups: ageGroups ?? this.ageGroups,
    availableOnly: availableOnly ?? this.availableOnly,
    showCancelled: showCancelled ?? this.showCancelled,
  );
}
