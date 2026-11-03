import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ReservationsScreen extends StatelessWidget {
  const ReservationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ClientScheduleScreen(
      reservationsOnly: true,
      actions: [
        IconButton(
          icon: const Icon(Icons.history),
          tooltip: AppLocalizations.of(context).trainingHistoryTitle,
          onPressed: () => context.push(clientTrainingHistoryPath),
        ),
      ],
    );
  }
}
