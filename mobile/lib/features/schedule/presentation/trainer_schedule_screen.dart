import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
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
    return Scaffold(
      appBar: AppBar(
        title: Text(AppLocalizations.of(context).navSchedule),
        actions: [
          IconButton(
            onPressed: () => showTraineeOverviewInfo(context),
            tooltip: AppLocalizations.of(context).traineeOverviewInfo,
            icon: Icon(Icons.info_outline, color: Colors.blue.shade700),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push(trainerAddClassPath),
        icon: const Icon(Icons.add),
        label: Text(AppLocalizations.of(context).addClassButton),
      ),
      body: scope == null
          ? const SizedBox()
          : ScheduleCalendar(
              key: ValueKey(scope),
              eventsFor: (window) {
                final query = (scope: scope, window: window);
                _visibleQuery = query;
                return trainerScheduleEventsProvider(query);
              },
              onRefresh: (window) => _refresh((scope: scope, window: window)),
              where: (event) => _filter.matches(event, scope.staffId),
              filtersBuilder: (context, events) => TrainerScheduleFilterBar(
                filter: _filter,
                rooms: {
                  for (final event in events) ...event.roomNames,
                }.toList(),
                onChanged: (filter) => setState(() => _filter = filter),
              ),
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
                return ScheduleEventTile(event, onTap: () => _openEvent(event));
              },
            ),
    );
  }

  void _openEvent(ScheduleEvent event) {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => TrainerEventDetailsScreen(eventId: event.id),
      ),
    );
  }
}
