import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/presentation/service_type_filter_labels.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_change_widgets.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_changes_banner.dart';
import 'package:mobile/features/schedule/presentation/widgets/trainer_schedule_filters.dart';
import 'package:mobile/features/trainings/presentation/trainer_event_details_screen.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The trainer's own classes and trainings, to plan the working day, with a
/// button to add a group class.
class TrainerScheduleScreen extends ConsumerStatefulWidget {
  const TrainerScheduleScreen({super.key});

  @override
  ConsumerState<TrainerScheduleScreen> createState() =>
      _TrainerScheduleScreenState();
}

class _TrainerScheduleScreenState extends ConsumerState<TrainerScheduleScreen>
    with WidgetsBindingObserver {
  /// Kept while the app runs; filters start empty after a restart.
  TrainerScheduleFilter _filter = const TrainerScheduleFilter();
  PersonalTrainingsQuery? _visibleQuery;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final query = _visibleQuery;
    if (state == AppLifecycleState.resumed && query != null) {
      _refresh(query);
    }
  }

  Future<void> _refresh(PersonalTrainingsQuery query) async {
    if (trainerOverviewScope(ref.read(authControllerProvider)) != query.scope) {
      return;
    }
    ref.invalidate(
      ownScheduleProvider((
        accountId: query.scope.accountId,
        window: query.window,
      )),
    );
    ref.invalidate(scheduleChangesProvider(TrainerChanges(query.scope)));
    ref.invalidate(ownPersonalTrainingsProvider(query));
    try {
      final request = ref.refresh(trainerScheduleEventsProvider(query).future);
      await request;
    } catch (_) {
      // ScheduleCalendar displays load failures and offers retry.
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    // Without service types the level and age group filters are left out.
    final serviceTypes =
        ref.watch(scheduleServiceTypesProvider).value ?? const {};
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context).navSchedule)),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _addEvent,
        icon: const Icon(Icons.add),
        label: Text(AppLocalizations.of(context).scheduleAdd),
      ),
      body: scope == null
          ? const SizedBox()
          : Column(
              children: [
                ScheduleChangesBanner(
                  owner: TrainerChanges(scope),
                  onOpen: _openEventById,
                ),
                Expanded(child: _calendar(scope, serviceTypes)),
              ],
            ),
    );
  }

  Future<void> _addEvent() async {
    final l10n = AppLocalizations.of(context);
    final path = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.groups_outlined),
              title: Text(l10n.eventKindClass),
              onTap: () => Navigator.pop(context, trainerAddClassPath),
            ),
            ListTile(
              leading: const Icon(Icons.forum_outlined),
              title: Text(l10n.eventKindConsultation),
              onTap: () => Navigator.pop(context, trainerAddConsultationPath),
            ),
          ],
        ),
      ),
    );
    if (!mounted || path == null) return;
    final event = await context.push<ScheduleEvent>(path);
    if (mounted && event != null) _openEvent(event);
  }

  Widget _calendar(
    TrainerOverviewScope scope,
    Map<String, ScheduleServiceType> serviceTypes,
  ) => ScheduleCalendar(
    key: ValueKey(scope),
    eventsFor: (window) {
      final query = (scope: scope, window: window);
      _visibleQuery = query;
      return trainerScheduleEventsProvider(query);
    },
    onRefresh: (window) => _refresh((scope: scope, window: window)),
    where: (event) => _filter.matches(event, scope.staffId, serviceTypes),
    filtersBuilder: (context, events) {
      final options = serviceTypeFilterOptions(
        serviceTypes,
        selectedLevels: _filter.levels,
        selectedAgeGroups: _filter.ageGroups,
      );
      return TrainerScheduleFilterBar(
        filter: _filter,
        rooms: {for (final event in events) ...event.roomNames}.toList(),
        levels: options.levels,
        ageGroups: options.ageGroups,
        onChanged: (filter) => setState(() => _filter = filter),
      );
    },
    eventBuilder: (context, event) {
      if (event is TraineeTraining) {
        final snapshot = ref
            .watch(ownPersonalTrainingsProvider(_visibleQuery!))
            .asData
            ?.value;
        if (snapshot == null) return const SizedBox();
        return PersonalTrainingScheduleTile(
          training: event,
          checkedAt: snapshot.checkedAt,
        );
      }
      if (_handedOver(event, scope.staffId)) {
        return _HandedOverTile(event);
      }
      return ScheduleEventTile(event, onTap: () => _openEvent(event));
    },
    movedBuilder: (context, event) => MovedAwayTile(
      name:
          event.name ??
          eventKindLabel(AppLocalizations.of(context), event.kind),
      startsAt: event.startsAt,
      usualStartsAt: event.seriesChange!.usualStartsAt!,
      onTap: _handedOver(event, scope.staffId) ? null : () => _openEvent(event),
    ),
  );

  void _openEvent(ScheduleEvent event) => _openEventById(event.id);

  void _openEventById(String eventId) {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => TrainerEventDetailsScreen(eventId: eventId),
      ),
    );
  }
}

/// Whether someone else runs the trainer's event this once: the schedule
/// shows it where the trainer expects it, but not as theirs.
bool _handedOver(ScheduleEvent event, String? staffId) =>
    staffId != null &&
    event.seriesChange?.usualInstructors != null &&
    event.staff.every((person) => person.staffId != staffId);

/// The placeholder of the trainer's usual class that another trainer runs
/// this once.
class _HandedOverTile extends StatelessWidget {
  const _HandedOverTile(this.event);

  final ScheduleEvent event;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final time = DateFormat('HH:mm');
    final leads = [
      for (final person in event.staff)
        if (person.isLead && person.displayName != null) person.displayName!,
    ];
    return UsualSlotTile(
      icon: Icons.swap_horiz,
      title:
          '${event.name ?? eventKindLabel(l10n, event.kind)}, '
          '${time.format(event.startsAt)}–${time.format(event.endsAt)}',
      subtitle: l10n.changeHandedOver(leads.join(', ')),
    );
  }
}
