import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/schedule/data/client_schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_event_tile.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_filters.dart';
import 'package:mobile/features/schedule/presentation/widgets/group_class_timeline.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_profile_picker.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_change_widgets.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_changes_banner.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/personal_training_screen.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ClientScheduleScreen extends ConsumerWidget {
  const ClientScheduleScreen({
    this.reservationsOnly = false,
    this.today,
    this.actions = const [],
    super.key,
  });
  final bool reservationsOnly;
  final DateTime? today;
  final List<Widget> actions;

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
        actions: actions,
      ),
      body: scope == null || session == null
          ? ClassMessage(l10n.classesNoProfile)
          : _ClientCalendar(
              key: ValueKey((session.accountId, reservationsOnly)),
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
  bool _allProfiles = false;
  bool get _showRelated =>
      ref
          .read(
            profileViewPreferencesProvider((
              accountId: widget.scope.accountId,
              profileId: widget.scope.profileId,
            )),
          )
          .value ??
      false;
  bool get _showFamily =>
      _allProfiles && _showRelated && widget.session.profiles.length > 1;
  ClientScheduleFilter _filter = const ClientScheduleFilter();
  final _serviceLabels = <String, String>{};
  final _instructorLabels = <String, String>{};

  ScheduleEvent _unwrap(ScheduleEvent event) =>
      event is ProfileScheduleEvent ? event.event : event;

  List<GroupClass> _classes(List<ScheduleEvent> events) => [
    for (final event in events.map(_unwrap))
      if (event is ClientScheduleEvent) event.groupClass,
  ];

  void _chooseProfile(Profile profile) {
    ref
        .read(authControllerProvider.notifier)
        .choose(SessionChoice(role: AppRole.client, profile: profile));
  }

  void _openClass(GroupClass event) => _openClassById(event.id);

  void _openClassById(String eventId) {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => GroupClassDetailsScreen(
          query: (scope: widget.scope, eventId: eventId),
        ),
      ),
    );
  }

  Widget _legend(BuildContext context, List<ScheduleEvent> events) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      ClientScheduleLegend(events: _classes(events)),
      if (events.map(_unwrap).any((event) => event is PersonalSession))
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(AppLocalizations.of(context).personalTitle),
        ),
    ],
  );

  /// The day's events and the placeholders of classes moved away from it, in
  /// the order of their time on the day.
  List<ScheduleEvent> _byTime(
    List<ScheduleEvent> events,
    List<ScheduleEvent> movedAway,
  ) {
    DateTime at(ScheduleEvent event) => movedAway.contains(event)
        ? event.seriesChange!.usualStartsAt!
        : event.startsAt;
    return [...events, ...movedAway]..sort((a, b) => at(a).compareTo(at(b)));
  }

  /// The placeholder at the usual time of a class moved this once.
  Widget _movedTile(BuildContext context, ScheduleEvent event) {
    final groupClass = (_unwrap(event) as ClientScheduleEvent).groupClass;
    return MovedAwayTile(
      name: groupClass.name ?? AppLocalizations.of(context).classesTitle,
      startsAt: groupClass.startsAt,
      usualStartsAt: groupClass.seriesChange!.usualStartsAt!,
      onTap: () => _openClass(groupClass),
    );
  }

  Widget _eventTile(BuildContext context, ScheduleEvent event) {
    final owned = event is ProfileScheduleEvent ? event : null;
    final scope = owned?.scope ?? widget.scope;
    final participant = owned == null
        ? null
        : ScheduleProfileBadge(session: widget.session, profile: owned.profile);
    event = _unwrap(event);
    void selectParticipant() {
      if (owned != null) _chooseProfile(owned.profile);
    }

    if (event is PersonalSession) {
      final personal = event;
      return PersonalTrainingTile(
        session: personal,
        participant: participant,
        onTap: () {
          if (!isCurrentBookingScope(ref, widget.scope)) return;
          selectParticipant();
          Navigator.of(context).push<void>(
            MaterialPageRoute(
              builder: (_) => PersonalTrainingScreen(
                query: (scope: scope, reservationId: personal.reservationId),
              ),
            ),
          );
        },
      );
    }
    final groupClass = (event as ClientScheduleEvent).groupClass;
    return ClientScheduleEventTile(
      event: groupClass,
      participant: participant,
      onTap: () {
        if (!isCurrentBookingScope(ref, widget.scope)) return;
        selectParticipant();
        // Choosing is synchronous; details now act for this participant.
        Navigator.of(context).push<void>(
          MaterialPageRoute(
            builder: (_) => GroupClassDetailsScreen(
              query: (scope: scope, eventId: groupClass.id),
            ),
          ),
        );
      },
    );
  }

  Future<void> _refresh(ScheduleWindow window) async {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    final scopes = _showFamily && widget.reservationsOnly
        ? [
            for (final p in widget.session.profiles)
              (accountId: widget.session.accountId, profileId: p.id),
          ]
        : [widget.scope];
    for (final scope in scopes) {
      final query = (scope: scope, window: window);
      if (widget.reservationsOnly) {
        ref.invalidate(personalTrainingsProvider(query));
        ref.invalidate(classReservationsProvider(query));
      } else {
        ref.invalidate(groupClassesProvider(query));
      }
      ref.invalidate(
        clientScheduleProvider((
          scope: scope,
          window: window,
          reservationsOnly: widget.reservationsOnly,
        )),
      );
    }
    if (widget.reservationsOnly) {
      ref.invalidate(scheduleChangesProvider(ParticipantChanges(widget.scope)));
    }
    final request = ref.refresh(_eventsFor(window).future);
    await request;
  }

  FutureProvider<List<ScheduleEvent>> _eventsFor(ScheduleWindow window) =>
      _showFamily && widget.reservationsOnly
      ? familyScheduleProvider((
          accountId: widget.session.accountId,
          window: window,
        ))
      : clientScheduleProvider((
          scope: widget.scope,
          window: window,
          reservationsOnly: widget.reservationsOnly,
        ));

  /// The service catalogue and the trainers for the offer's filters,
  /// refreshed on every build; empty while they load or when they cannot be
  /// loaded.
  Map<String, ScheduleServiceType> _serviceTypes = const {};
  List<StudioTrainer> _trainers = const [];

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
      serviceTypes: _serviceTypes,
      trainers: _trainers,
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
    final showRelated =
        widget.reservationsOnly &&
        (ref
                .watch(
                  profileViewPreferencesProvider((
                    accountId: widget.scope.accountId,
                    profileId: widget.scope.profileId,
                  )),
                )
                .value ??
            false);
    // Reservations use the participant picker; offer filters stay independent.
    final filtered = !widget.reservationsOnly;
    final serviceTypes = _serviceTypes = filtered
        ? ref.watch(scheduleServiceTypesProvider).value ?? const {}
        : const <String, ScheduleServiceType>{};
    _trainers = filtered
        ? ref.watch(studioTrainersProvider).value ?? const []
        : const [];
    return Column(
      children: [
        if (showRelated && widget.session.profiles.length > 1)
          ScheduleProfilePicker(
            session: widget.session,
            allSelected: _allProfiles,
            onAll: () => setState(() => _allProfiles = true),
            onProfile: (profile) {
              setState(() => _allProfiles = false);
              _chooseProfile(profile);
            },
          ),
        if (widget.reservationsOnly)
          ScheduleChangesBanner(
            owner: ParticipantChanges(widget.scope),
            onOpen: _openClassById,
          ),
        Expanded(
          child: ScheduleCalendar(
            today: widget.today,
            onRefresh: _refresh,
            eventsFor: _eventsFor,
            where: (source) {
              final event = _unwrap(source);
              return event is PersonalSession
                  ? widget.reservationsOnly
                  : event is ClientScheduleEvent &&
                        (!filtered ||
                            _filter.matches(event.groupClass, serviceTypes));
            },
            filtersBuilder: filtered ? _filters : null,
            eventBuilder: _eventTile,
            movedBuilder: _movedTile,
            dayBuilder: (context, events, day, movedAway) =>
                events.isEmpty && movedAway.isEmpty
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
                : widget.reservationsOnly
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(12),
                    children: [
                      for (final event in _byTime(events, movedAway))
                        movedAway.contains(event)
                            ? _movedTile(context, event)
                            : _eventTile(context, event),
                      _legend(context, events),
                    ],
                  )
                : GroupClassTimeline(
                    events: _classes(events),
                    movedAway: _classes(movedAway),
                    day: day,
                    onSelect: _openClass,
                    footer: _legend(context, events),
                  ),
            eventColor: (source, scheme) {
              final event = _unwrap(source);
              return event.status == EventStatus.cancelled
                  ? scheme.error
                  : event is PersonalSession
                  ? scheme.primary
                  : classServiceColor(
                      (event as ClientScheduleEvent).groupClass,
                      scheme,
                    );
            },
            legendBuilder: _legend,
          ),
        ),
      ],
    );
  }
}
