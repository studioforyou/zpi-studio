import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Booking rows retain the shared calendar's Card, stripe and typography.
class ClientScheduleEventTile extends StatelessWidget {
  const ClientScheduleEventTile({
    required this.event,
    required this.onTap,
    super.key,
  });
  final GroupClass event;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
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
      child: InkWell(
        onTap: onTap,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(width: 6, color: color),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 6,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              classTime(event),
                              style: theme.textTheme.labelLarge,
                            ),
                          ),
                          const Icon(Icons.chevron_right, size: 20),
                        ],
                      ),
                      Text(
                        event.name ?? l10n.classesTitle,
                        style: theme.textTheme.titleMedium?.copyWith(
                          decoration: cancelled
                              ? TextDecoration.lineThrough
                              : null,
                          color: cancelled ? theme.colorScheme.error : null,
                        ),
                      ),
                      Text(
                        teachers.isEmpty
                            ? l10n.classesInstructorPending
                            : teachers,
                        style: theme.textTheme.bodySmall,
                      ),
                      Text(
                        event.roomNames.isEmpty
                            ? l10n.classesRoomPending
                            : event.roomNames.join(', '),
                        style: theme.textTheme.bodySmall,
                      ),
                      if (event.isReserved)
                        Row(
                          children: [
                            Icon(
                              Icons.check_circle_outline,
                              size: 16,
                              color: theme.colorScheme.primary,
                            ),
                            const SizedBox(width: 6),
                            Flexible(
                              child: Text(
                                l10n.classesReserved,
                                style: theme.textTheme.labelMedium,
                              ),
                            ),
                          ],
                        ),
                      if (cancelled)
                        Text(
                          l10n.eventStatusCancelled,
                          style: TextStyle(color: theme.colorScheme.error),
                        )
                      else
                        ClassOccupancy(event),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class ClientScheduleLegend extends StatelessWidget {
  const ClientScheduleLegend({required this.events, super.key});
  final List<GroupClass> events;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final services = <String, GroupClass>{};
    for (final event in events) {
      services.putIfAbsent(
        event.serviceTypeId ?? event.serviceTypeName ?? '',
        () => event,
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Wrap(
        spacing: 12,
        runSpacing: 8,
        children: [
          for (final event in services.values)
            _entry(
              context,
              classServiceColor(event, theme.colorScheme),
              event.serviceTypeName ?? l10n.classesTitle,
            ),
          if (events.any((event) => event.status == 'CANCELLED'))
            _entry(context, theme.colorScheme.error, l10n.eventStatusCancelled),
        ],
      ),
    );
  }

  Widget _entry(BuildContext context, Color color, String label) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
      const SizedBox(width: 5),
      Flexible(
        child: Text(label, style: Theme.of(context).textTheme.bodySmall),
      ),
    ],
  );
}
