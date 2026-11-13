import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_card_view.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_profile_view.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class TraineeOverviewScreen extends ConsumerWidget {
  const TraineeOverviewScreen({required this.customerId, super.key});
  final String customerId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    if (scope == null) return const SizedBox();
    final query = (scope: scope, customerId: customerId);
    return _OverviewScreen(key: ValueKey(query), query: query);
  }
}

class _OverviewScreen extends ConsumerStatefulWidget {
  const _OverviewScreen({super.key, required this.query});
  final TraineeOverviewQuery query;

  @override
  ConsumerState<_OverviewScreen> createState() => _OverviewScreenState();
}

class _OverviewScreenState extends ConsumerState<_OverviewScreen>
    with WidgetsBindingObserver {
  TraineeCalendarQuery? _calendarQuery;

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
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    if (trainerOverviewScope(ref.read(authControllerProvider)) !=
        widget.query.scope) {
      return;
    }
    ref.invalidate(traineeOverviewsProvider(widget.query.scope));
    ref.invalidate(traineeOverviewProvider(widget.query));
    // Another trainer of the trainee may have changed the shared card.
    ref.invalidate(traineeCardProvider(widget.query));
    final calendar = _calendarQuery;
    if (calendar != null) ref.invalidate(traineeCalendarProvider(calendar));
    try {
      await ref.read(traineeOverviewProvider(widget.query).future);
    } catch (_) {
      // The observed request renders the failure and keeps retry available.
    }
  }

  Future<void> _refreshCalendar(ScheduleWindow window) async {
    await _refresh();
    await ref.read(
      traineeCalendarProvider((
        scope: widget.query.scope,
        customerId: widget.query.customerId,
        window: window,
      )).future,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final result = ref.watch(traineeOverviewProvider(widget.query));
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            result.value?.trainee.displayName ?? l10n.traineeOverviewTitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          actions: [
            IconButton(
              onPressed: _refresh,
              tooltip: l10n.traineeOverviewRefresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.traineeOverviewPackages),
              Tab(text: l10n.traineeOverviewHistory),
              Tab(text: l10n.traineeCardTab),
              Tab(text: l10n.traineeProfileTab),
            ],
          ),
        ),
        body: result.when(
          skipLoadingOnRefresh: true,
          skipLoadingOnReload: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                spacing: 12,
                children: [
                  Text(
                    l10n.traineeOverviewLoadError,
                    textAlign: TextAlign.center,
                  ),
                  OutlinedButton(
                    onPressed: _refresh,
                    child: Text(l10n.traineesRetry),
                  ),
                ],
              ),
            ),
          ),
          data: (details) => TabBarView(
            children: [
              RefreshIndicator(
                onRefresh: _refresh,
                child: ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.all(16),
                  children: [
                    if (details.trainee.nextTraining case final next?)
                      TraineeNextTraining(
                        next,
                        checkedAt: details.checkedAt,
                        lowBalance: details.trainee.passes.any(
                          (pass) => pass.id == next.passId && pass.lowBalance,
                        ),
                      )
                    else
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        child: Text(l10n.traineeOverviewNoNext),
                      ),
                    if (details.trainee.attentionTraining case final attention?)
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: TraineeTrainingLink(
                          attention,
                          checkedAt: details.checkedAt,
                          attention: true,
                        ),
                      ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            l10n.traineeOverviewPackages,
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                        ),
                        IconButton(
                          onPressed: () => showTraineePackageInfo(context),
                          tooltip: l10n.traineeOverviewPackageHelp,
                          icon: Icon(
                            Icons.info_outline,
                            color: Colors.blue.shade700,
                          ),
                        ),
                      ],
                    ),
                    if (details.trainee.passes.isEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 24),
                        child: Text(
                          details.trainee.availability ==
                                  'NO_QUALIFIED_SERVICES'
                              ? l10n.traineeOverviewNoQualifiedServices
                              : l10n.traineeOverviewNoPackages,
                        ),
                      ),
                    if ((details.trainee.primaryPass ??
                            details.trainee.passes.firstOrNull)
                        case final pass?)
                      TraineePassCard(pass),
                    if (details.trainee.passes.length > 1)
                      ExpansionTile(
                        tilePadding: const EdgeInsets.symmetric(horizontal: 4),
                        shape: const Border(),
                        collapsedShape: const Border(),
                        title: Text(
                          l10n.traineeOverviewOtherPackages(
                            details.trainee.passes.length - 1,
                          ),
                        ),
                        children: [
                          for (final pass in details.trainee.passes)
                            if (pass.id !=
                                (details.trainee.primaryPass ??
                                        details.trainee.passes.firstOrNull)
                                    ?.id)
                              TraineePassCard(pass),
                        ],
                      ),
                  ],
                ),
              ),
              ScheduleCalendar(
                today: details.checkedOn,
                eventsFor: (window) {
                  final query = (
                    scope: widget.query.scope,
                    customerId: widget.query.customerId,
                    window: window,
                  );
                  _calendarQuery = query;
                  return traineeCalendarProvider(query);
                },
                onRefresh: _refreshCalendar,
                showLegend: false,
                eventBuilder: (context, event) => TraineeTrainingCard(
                  event as TraineeTraining,
                  checkedAt: details.checkedAt,
                  lowBalance: details.trainee.passes.any(
                    (pass) => pass.id == event.passId && pass.lowBalance,
                  ),
                ),
              ),
              TraineeCardView(query: widget.query),
              TraineeProfileView(query: widget.query),
            ],
          ),
        ),
      ),
    );
  }
}
