import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);
    final sectionStyle = Theme.of(context).textTheme.titleMedium;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.navProfile)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(session.displayName),
            if (session.roles.length > 1) ...[
              Text(l10n.profileRoleSection, style: sectionStyle),
              Wrap(
                children: [
                  for (final role in session.roles)
                    ChoiceChip(
                      label: Text(switch (role) {
                        AppRole.client => l10n.roleClient,
                        AppRole.trainer => l10n.roleTrainer,
                      }),
                      selected: role == session.activeRole,
                      onSelected: (_) => controller.selectRole(role),
                    ),
                ],
              ),
            ],
            if (session.activeRole == AppRole.client &&
                session.profiles.length > 1) ...[
              Text(l10n.profileActiveProfileSection, style: sectionStyle),
              Wrap(
                children: [
                  for (final profile in session.profiles)
                    ChoiceChip(
                      label: Text(profile.displayName),
                      selected: profile.id == session.activeProfileId,
                      onSelected: (_) => controller.selectProfile(profile.id),
                    ),
                ],
              ),
            ],
            if (session.activeRole == AppRole.trainer)
              ListTile(
                leading: const Icon(Icons.schedule),
                title: Text(l10n.navAvailability),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/trainer/profile/availability'),
              ),
            OutlinedButton(
              onPressed: controller.logout,
              child: Text(l10n.logoutButton),
            ),
          ],
        ),
      },
    );
  }
}
