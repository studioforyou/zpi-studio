import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// One of the trainer's events with its participants, to prepare for working
/// with the group.
class TrainerEventDetailsScreen extends ConsumerWidget {
  const TrainerEventDetailsScreen({required this.eventId, super.key});

  final String eventId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context).classesDetails)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => _Details(
          query: (accountId: session.accountId, eventId: eventId),
        ),
      },
    );
  }
}

class _Details extends ConsumerWidget {
  const _Details({required this.query});

  final EventParticipantsQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final eventProvider = ownScheduleEventProvider(query);
    return RefreshIndicator(
      onRefresh: () => _refresh(ref),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: ref
            .watch(eventProvider)
            .when(
              skipLoadingOnRefresh: false,
              loading: () => const [
                Padding(
                  padding: EdgeInsets.all(48),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ],
              error: (_, _) => [ClassesLoadError(onRetry: () => _retry(ref))],
              data: (event) => [
                _EventSummary(event),
                _ParticipantsSection(event: event, query: query),
              ],
            ),
      ),
    );
  }

  Future<void> _refresh(WidgetRef ref) async {
    try {
      await Future.wait([
        ref.refresh(ownScheduleEventProvider(query).future),
        ref.refresh(eventParticipantsProvider(query).future),
      ]);
    } catch (_) {
      // The observed providers display load failures and offer a retry.
    }
  }

  void _retry(WidgetRef ref) {
    ref.invalidate(ownScheduleEventProvider(query));
    ref.invalidate(eventParticipantsProvider(query));
  }
}

/// The event's time, place and staff, in the calendar's card style.
class _EventSummary extends StatelessWidget {
  const _EventSummary(this.event);

  final ScheduleEvent event;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final cancelled = event.status == EventStatus.cancelled;
    final time = DateFormat('HH:mm');
    final name = event.name ?? eventKindLabel(l10n, event.kind);
    final leads = _names(event, lead: true);
    final assistants = _names(event, lead: false);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              width: 6,
              color: scheduleEventColor(event, theme.colorScheme),
            ),
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
                          '${time.format(event.startsAt)}–'
                          '${time.format(event.endsAt)}',
                          style: theme.textTheme.labelLarge,
                        ),
                        Text(
                          name,
                          style: theme.textTheme.titleLarge?.copyWith(
                            decoration: cancelled
                                ? TextDecoration.lineThrough
                                : null,
                            color: cancelled ? theme.colorScheme.error : null,
                          ),
                        ),
                        Text(
                          [
                            eventKindLabel(l10n, event.kind),
                            if (event.serviceTypeName case final service?
                                when service != name)
                              service,
                          ].join(' · '),
                          style: theme.textTheme.bodySmall,
                        ),
                      ],
                    ),
                    ClassInfoLine(
                      Icons.calendar_today_outlined,
                      classDate(event.startsAt),
                    ),
                    ClassInfoLine(
                      Icons.person_outline,
                      '${l10n.classesInstructor}: '
                      '${leads.isEmpty ? l10n.classesInstructorPending : leads}',
                    ),
                    if (assistants.isNotEmpty)
                      ClassInfoLine(
                        Icons.people_alt_outlined,
                        '${l10n.trainerEventAssistant}: $assistants',
                      ),
                    ClassInfoLine(
                      Icons.meeting_room_outlined,
                      '${l10n.classesRoom}: '
                      '${event.roomNames.isEmpty ? l10n.classesRoomPending : event.roomNames.join(', ')}',
                    ),
                    if (cancelled)
                      ClassInfoLine(Icons.event_busy, l10n.eventStatusCancelled)
                    else if (event.status == EventStatus.completed)
                      ClassInfoLine(
                        Icons.event_available,
                        l10n.eventStatusCompleted,
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

  static String _names(ScheduleEvent event, {required bool lead}) => event.staff
      .where((person) => person.isLead == lead && person.displayName != null)
      .map((person) => person.displayName!)
      .join(', ');
}

class _ParticipantsSection extends ConsumerWidget {
  const _ParticipantsSection({required this.event, required this.query});

  final ScheduleEvent event;
  final EventParticipantsQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = eventParticipantsProvider(query);
    final participants = ref.watch(provider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 20),
        const Divider(),
        const SizedBox(height: 16),
        Text(switch (participants) {
          AsyncData(:final value) => l10n.trainerEventParticipants(
            value.length,
          ),
          _ => l10n.classesParticipants,
        }, style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        participants.when(
          skipLoadingOnRefresh: false,
          loading: () => const Padding(
            padding: EdgeInsets.all(12),
            child: Center(
              child: SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          ),
          error: (_, _) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.classesParticipantsError),
              TextButton.icon(
                onPressed: () => ref.invalidate(provider),
                icon: const Icon(Icons.refresh),
                label: Text(l10n.scheduleRetryButton),
              ),
            ],
          ),
          data: (participants) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            spacing: 8,
            children: [
              ClassOccupancy.counts(
                reserved: participants.length,
                capacity: event.capacity,
              ),
              if (participants.isEmpty)
                Text(l10n.trainerEventParticipantsEmpty)
              else
                for (final participant in participants)
                  _ParticipantTile(participant, day: event.startsAt),
            ],
          ),
        ),
      ],
    );
  }
}

class _ParticipantTile extends StatelessWidget {
  const _ParticipantTile(this.participant, {required this.day});

  final EventParticipant participant;

  /// The day of the event, on which the age is given.
  final DateTime day;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final age = participant.ageOn(day);
    final details = [
      if (participant.isChild) l10n.traineesChild,
      if (age != null) l10n.trainerEventParticipantAge(age),
    ];
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(child: Text(_initials(participant.displayName))),
      title: Text(participant.displayName),
      subtitle: details.isEmpty ? null : Text(details.join(' · ')),
    );
  }

  static String _initials(String name) => name
      .split(' ')
      .where((part) => part.isNotEmpty)
      .take(2)
      .map((part) => part[0].toUpperCase())
      .join();
}
