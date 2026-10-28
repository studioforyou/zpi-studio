import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/schedule/presentation/widgets/trainer_schedule_filters.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The trainer's own classes and trainings, to plan the working day.
class TrainerScheduleScreen extends ConsumerStatefulWidget {
  const TrainerScheduleScreen({super.key});

  @override
  ConsumerState<TrainerScheduleScreen> createState() =>
      _TrainerScheduleScreenState();
}

class _TrainerScheduleScreenState extends ConsumerState<TrainerScheduleScreen> {
  /// Kept while the app runs; filters start empty after a restart.
  TrainerScheduleFilter _filter = const TrainerScheduleFilter();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context).navSchedule)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ScheduleCalendar(
          eventsFor: (window) => ownScheduleProvider((
            accountId: session.accountId,
            window: window,
          )),
          where: (event) => _filter.matches(event, session.staffProfileId),
          filtersBuilder: (context, events) => TrainerScheduleFilterBar(
            filter: _filter,
            rooms: {for (final event in events) ...event.roomNames}.toList(),
            onChanged: (filter) => setState(() => _filter = filter),
          ),
        ),
      },
    );
  }
}
