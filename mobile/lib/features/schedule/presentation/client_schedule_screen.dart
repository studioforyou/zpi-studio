import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/client_schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_event_tile.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_filters.dart';
import 'package:mobile/features/schedule/presentation/widgets/group_class_timeline.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ClientScheduleScreen extends ConsumerWidget {
  const ClientScheduleScreen({
    this.reservationsOnly = false,
    this.today,
    super.key,
  });
  final bool reservationsOnly;
  final DateTime? today;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final state = ref.watch(authControllerProvider);
    final session = clientSession(state);
    final scope = bookingScope(state);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          reservationsOnly ? l10n.navMySchedule : l10n.classesOfferTitle,
        ),
      ),
      body: scope == null || session == null
          ? ClassMessage(l10n.classesNoProfile)
          : _ClientCalendar(
              key: ValueKey((scope, reservationsOnly)),
              scope: scope,
              session: session,
              reservationsOnly: reservationsOnly,
              today: today,
            ),
    );
  }
}

class _ClientCalendar extends ConsumerStatefulWidget {
  const _ClientCalendar({
    required this.scope,
    required this.session,
    required this.reservationsOnly,
    required this.today,
    super.key,
  });
  final BookingScope scope;
  final Session session;
  final bool reservationsOnly;
  final DateTime? today;

  @override
  ConsumerState<_ClientCalendar> createState() => _ClientCalendarState();
}

class _ClientCalendarState extends ConsumerState<_ClientCalendar> {
  ClientScheduleFilter _filter = const ClientScheduleFilter();
  final _serviceLabels = <String, String>{};
  final _instructorLabels = <String, String>{};

  List<GroupClass> _classes(List<ScheduleEvent> events) => [
    for (final event in events)
      if (event is ClientScheduleEvent) event.groupClass,
  ];

  void _openClass(GroupClass event) {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => GroupClassDetailsScreen(
          query: (scope: widget.scope, eventId: event.id),
        ),
      ),
    );
  }

  Widget _legend(BuildContext context, List<ScheduleEvent> events) =>
      ClientScheduleLegend(events: _classes(events));

  Future<void> _refresh(ScheduleWindow window) async {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    final query = (scope: widget.scope, window: window);
    ref.invalidate(
      widget.reservationsOnly
          ? classReservationsProvider(query)
          : groupClassesProvider(query),
    );
    final request = ref.refresh(
      clientScheduleProvider((
        scope: widget.scope,
        window: window,
        reservationsOnly: widget.reservationsOnly,
      )).future,
    );
    await request;
  }

  Widget _filters(BuildContext context, List<ScheduleEvent> events) {
    final classes = _classes(events);
    final l10n = AppLocalizations.of(context);
    for (final event in classes) {
      _serviceLabels[ClientScheduleFilter.serviceKey(event)] =
          event.serviceTypeName ?? event.name ?? l10n.classesTitle;
      for (final person in event.staff) {
        if (person.isLead && person.displayName != null) {
          _instructorLabels[person.staffId] = person.displayName!;
        }
      }
    }
    return ClientScheduleFilterBar(
      filter: _filter,
      events: classes,
      selectedServiceLabels: {
        for (final id in _filter.services)
          if (_serviceLabels[id] case final String label) id: label,
      },
      selectedInstructorLabels: {
        for (final id in _filter.instructors)
          if (_instructorLabels[id] case final String label) id: label,
      },
      reservationsOnly: widget.reservationsOnly,
      onChanged: (filter) {
        if (mounted && isCurrentBookingScope(ref, widget.scope)) {
          setState(() => _filter = filter);
        }
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      children: [
        if (widget.session.profiles.length > 1)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: DropdownButtonFormField<String>(
              initialValue: widget.scope.profileId,
              isExpanded: true,
              decoration: InputDecoration(
                labelText: l10n.profileActiveProfileSection,
                prefixIcon: const Icon(Icons.person_outline),
                border: const OutlineInputBorder(),
                isDense: true,
              ),
              items: [
                for (final profile in widget.session.profiles)
                  DropdownMenuItem(
                    value: profile.id,
                    child: Text(profile.displayName),
                  ),
              ],
              onChanged: (id) {
                if (id != null) {
                  ref.read(authControllerProvider.notifier).selectProfile(id);
                }
              },
            ),
          ),
        Expanded(
          child: ScheduleCalendar(
            today: widget.today,
            onRefresh: _refresh,
            eventsFor: (window) => clientScheduleProvider((
              scope: widget.scope,
              window: window,
              reservationsOnly: widget.reservationsOnly,
            )),
            where: (event) =>
                event is ClientScheduleEvent &&
                _filter.matches(event.groupClass),
            filtersBuilder: _filters,
            eventBuilder: (context, event) {
              final groupClass = (event as ClientScheduleEvent).groupClass;
              return ClientScheduleEventTile(
                event: groupClass,
                onTap: () => _openClass(groupClass),
              );
            },
            dayBuilder: (context, events, day) => events.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: [
                      ClassMessage(
                        widget.reservationsOnly
                            ? l10n.classesReservationsEmpty
                            : _filter.activeCount > 0
                            ? l10n.classesNoFilteredDayEvents
                            : l10n.scheduleEmptyDay,
                      ),
                    ],
                  )
                : GroupClassTimeline(
                    events: _classes(events),
                    day: day,
                    onSelect: _openClass,
                    footer: _legend(context, events),
                  ),
            eventColor: (event, scheme) => event.status == EventStatus.cancelled
                ? scheme.error
                : classServiceColor(
                    (event as ClientScheduleEvent).groupClass,
                    scheme,
                  ),
            legendBuilder: _legend,
          ),
        ),
      ],
    );
  }
}
