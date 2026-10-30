import 'package:mobile/features/trainings/data/group_classes_api.dart';

class ClientScheduleFilter {
  const ClientScheduleFilter({
    this.services = const {},
    this.instructors = const {},
    this.availableOnly = false,
    this.showCancelled = true,
  });

  final Set<String> services;
  final Set<String> instructors;
  final bool availableOnly;
  final bool showCancelled;

  int get activeCount =>
      (services.isEmpty ? 0 : 1) +
      (instructors.isEmpty ? 0 : 1) +
      (availableOnly ? 1 : 0) +
      (showCancelled ? 0 : 1);

  static String serviceKey(GroupClass event) =>
      event.serviceTypeId ?? event.serviceTypeName ?? event.name ?? '';

  bool matches(GroupClass event) =>
      (services.isEmpty || services.contains(serviceKey(event))) &&
      (instructors.isEmpty ||
          event.staff.any(
            (person) => person.isLead && instructors.contains(person.staffId),
          )) &&
      (showCancelled || event.status != 'CANCELLED') &&
      (!availableOnly ||
          (event.status == 'SCHEDULED' &&
              event.startsAt.isAfter(DateTime.now()) &&
              !event.isFull));

  ClientScheduleFilter copyWith({
    Set<String>? services,
    Set<String>? instructors,
    bool? availableOnly,
    bool? showCancelled,
  }) => ClientScheduleFilter(
    services: services ?? this.services,
    instructors: instructors ?? this.instructors,
    availableOnly: availableOnly ?? this.availableOnly,
    showCancelled: showCancelled ?? this.showCancelled,
  );
}
