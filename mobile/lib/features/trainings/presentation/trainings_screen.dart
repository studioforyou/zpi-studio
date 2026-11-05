import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Own trainees and training groups. Sessions live in the main trainer schedule.
class TrainingsScreen extends StatelessWidget {
  const TrainingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l10n.navTrainings),
          actions: [
            IconButton(
              onPressed: () => showTraineeOverviewInfo(context),
              tooltip: l10n.traineeOverviewInfo,
              icon: Icon(Icons.info_outline, color: Colors.blue.shade700),
            ),
          ],
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.trainingsTabTrainees),
              Tab(text: l10n.trainingsTabGroups),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            const TraineesTab(),
            Center(child: Text(l10n.placeholderComingSoon)),
          ],
        ),
      ),
    );
  }
}
