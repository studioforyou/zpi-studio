import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The person and the role shown for [choice].
({String name, String role}) choiceLabels(
  AppLocalizations l10n,
  Session session,
  SessionChoice choice,
) {
  final profile = choice.profile;
  return (
    name: profile?.displayName ?? session.displayName,
    role: switch (choice.role) {
      AppRole.trainer => l10n.roleTrainer,
      AppRole.client when profile != null && profile.isChild => l10n.roleChild,
      AppRole.client => l10n.roleClient,
    },
  );
}

/// Shown after signing in, and from the profile tab, when the account can use
/// the app in more than one way: as a client for itself or for a child, or as
/// a trainer.
class RolePickerScreen extends ConsumerWidget {
  const RolePickerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);

    return Scaffold(
      body: SafeArea(
        child: switch (ref.watch(authControllerProvider)) {
          AuthRestoring() || Unauthenticated() => const SizedBox(),
          Authenticated(:final session) => Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  Text(
                    l10n.rolePickerTitle,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 24),
                  Wrap(
                    alignment: WrapAlignment.center,
                    spacing: 16,
                    runSpacing: 16,
                    children: [
                      for (final choice in session.choices)
                        _ChoiceTile(
                          labels: choiceLabels(l10n, session, choice),
                          onTap: () => controller.choose(choice),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        },
      ),
    );
  }
}

class _ChoiceTile extends StatelessWidget {
  const _ChoiceTile({required this.labels, required this.onTap});

  final ({String name, String role}) labels;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: SizedBox(
        width: 120,
        child: Column(
          children: [
            CircleAvatar(
              radius: 40,
              child: Text(labels.name.characters.firstOrNull ?? ''),
            ),
            const SizedBox(height: 8),
            Text(labels.name, textAlign: TextAlign.center),
            Text(labels.role, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
