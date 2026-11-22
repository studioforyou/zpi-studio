import 'package:flutter/material.dart';
import 'package:mobile/features/schedule/presentation/substitution_offers_screen.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Trainees, groups and substitutions. Sessions live in the main trainer schedule.
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
            Builder(
              builder: (context) {
                final controller = DefaultTabController.of(context);
                return ListenableBuilder(
                  listenable: controller,
                  builder: (context, _) => controller.index == 2
                      ? const SubstitutionOffersInfoButton()
                      : const SizedBox.shrink(),
                );
              },
            ),
          ],
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.trainingsTabTrainees),
              Tab(text: l10n.trainingsTabGroups),
              Tab(text: l10n.substitutionOffersTitle),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            const TraineesTab(),
            Center(child: Text(l10n.placeholderComingSoon)),
            const SubstitutionOffersScreen(embedded: true),
          ],
        ),
      ),
    );
  }
}
