import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/features/trainings/presentation/personal_trainings_tab.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// A shared personal-training calendar, own trainees and training groups.
class TrainingsScreen extends StatelessWidget {
  const TrainingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DefaultTabController(
      length: 3,
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
              Tab(text: l10n.navSchedule),
              Tab(text: l10n.trainingsTabTrainees),
              Tab(text: l10n.trainingsTabGroups),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            const PersonalTrainingsTab(),
            const TraineesTab(),
            Center(child: Text(l10n.placeholderComingSoon)),
          ],
        ),
      ),
    );
  }
}
