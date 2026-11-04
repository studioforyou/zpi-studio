import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// All personal sessions with the trainer's own current trainees, in one calendar.
class PersonalTrainingsTab extends ConsumerWidget {
  const PersonalTrainingsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    if (scope == null) return const SizedBox();
    return _PersonalCalendar(key: ValueKey(scope), scope: scope);
  }
}

class _PersonalCalendar extends ConsumerStatefulWidget {
  const _PersonalCalendar({super.key, required this.scope});
  final TrainerOverviewScope scope;

  @override
  ConsumerState<_PersonalCalendar> createState() => _PersonalCalendarState();
}

class _PersonalCalendarState extends ConsumerState<_PersonalCalendar>
    with WidgetsBindingObserver {
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
      _refresh(query.window);
    }
  }

  Future<void> _refresh(ScheduleWindow window) async {
    if (trainerOverviewScope(ref.read(authControllerProvider)) !=
        widget.scope) {
      return;
    }
    final query = (scope: widget.scope, window: window);
    ref.invalidate(ownPersonalTrainingsProvider(query));
    try {
      final request = ref.refresh(
        ownPersonalTrainingEventsProvider(query).future,
      );
      await request;
    } catch (_) {
      // ScheduleCalendar shows the failure and its retry action.
    }
  }

  @override
  Widget build(BuildContext context) => ScheduleCalendar(
    eventsFor: (window) {
      final query = (scope: widget.scope, window: window);
      _visibleQuery = query;
      return ownPersonalTrainingEventsProvider(query);
    },
    showLegend: false,
    onRefresh: _refresh,
    eventBuilder: (context, event) {
      final training = event as TraineeTraining;
      return _PersonalTrainingRow(training: training, query: _visibleQuery!);
    },
  );
}

class _PersonalTrainingRow extends ConsumerWidget {
  const _PersonalTrainingRow({required this.training, required this.query});
  final TraineeTraining training;
  final PersonalTrainingsQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref
        .watch(ownPersonalTrainingsProvider(query))
        .asData
        ?.value;
    if (snapshot == null) return const SizedBox();
    final theme = Theme.of(context);
    final time =
        '${DateFormat('HH:mm').format(training.startsAt)}–${DateFormat('HH:mm').format(training.endsAt)}';
    final name =
        training.customerName ??
        AppLocalizations.of(context).traineeOverviewParticipant;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => showTraineeTraining(context, training, snapshot.checkedAt),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 7,
            children: [
              LayoutBuilder(
                builder: (context, constraints) {
                  final largeText =
                      MediaQuery.textScalerOf(context).scale(14) > 18;
                  final timeLabel = Text(
                    time,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  );
                  final nameLabel = Text(
                    name,
                    style: theme.textTheme.titleMedium,
                  );
                  if (largeText && constraints.maxWidth < 320) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 4,
                      children: [timeLabel, nameLabel],
                    );
                  }
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      timeLabel,
                      const SizedBox(width: 12),
                      Expanded(child: nameLabel),
                    ],
                  );
                },
              ),
              if (training.roomNames.isNotEmpty)
                Text(
                  training.roomNames.join(' · '),
                  style: theme.textTheme.bodySmall,
                ),
              TraineeTrainingState(training, checkedAt: snapshot.checkedAt),
            ],
          ),
        ),
      ),
    );
  }
}
