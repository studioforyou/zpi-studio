import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The same card treatment as the shared schedule's event tiles.
class GroupClassSummary extends StatelessWidget {
  const GroupClassSummary({
    required this.event,
    required this.participant,
    super.key,
  });

  final GroupClass event;
  final String? participant;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final cancelled = event.status == 'CANCELLED';
    final color = cancelled
        ? theme.colorScheme.error
        : classServiceColor(event, theme.colorScheme);
    final teachers = event.staff
        .where((person) => person.isLead && person.displayName != null)
        .map((person) => person.displayName!)
        .join(', ');
    return Card(
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(width: 6, color: color),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 12,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 4,
                      children: [
                        Text(
                          classTime(event),
                          style: theme.textTheme.labelLarge,
                        ),
                        Text(
                          event.name ?? l10n.classesTitle,
                          style: theme.textTheme.titleLarge?.copyWith(
                            decoration: cancelled
                                ? TextDecoration.lineThrough
                                : null,
                            color: cancelled ? theme.colorScheme.error : null,
                          ),
                        ),
                        if (event.serviceTypeName != null &&
                            event.serviceTypeName != event.name)
                          Text(
                            event.serviceTypeName!,
                            style: theme.textTheme.bodySmall,
                          ),
                      ],
                    ),
                    ClassInfoLine(
                      Icons.calendar_today_outlined,
                      classDate(event.startsAt),
                    ),
                    if (participant case final name?)
                      ClassInfoLine(
                        Icons.account_circle_outlined,
                        l10n.classesReserveFor(name),
                      ),
                    ClassInfoLine(
                      Icons.person_outline,
                      '${l10n.classesInstructor}: '
                      '${teachers.isEmpty ? l10n.classesInstructorPending : teachers}',
                    ),
                    ClassInfoLine(
                      Icons.meeting_room_outlined,
                      '${l10n.classesRoom}: '
                      '${event.roomNames.isEmpty ? l10n.classesRoomPending : event.roomNames.join(', ')}',
                    ),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(Icons.people_outline, size: 18),
                        const SizedBox(width: 8),
                        Expanded(child: ClassOccupancy(event)),
                      ],
                    ),
                    if (cancelled)
                      ClassInfoLine(
                        Icons.event_busy,
                        l10n.eventStatusCancelled,
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
