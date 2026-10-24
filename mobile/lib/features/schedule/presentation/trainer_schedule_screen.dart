import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The trainer's own classes and trainings, to plan the working day.
class TrainerScheduleScreen extends ConsumerWidget {
  const TrainerScheduleScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context).navSchedule)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ScheduleCalendar(
          eventsFor: (window) => ownScheduleProvider((
            accountId: session.accountId,
            window: window,
          )),
        ),
      },
    );
  }
}
