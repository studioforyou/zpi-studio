import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The trainer's trainings: the participants they work with and, later,
/// their training groups.
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
