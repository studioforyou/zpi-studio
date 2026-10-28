import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/data/staff_profile_provider.dart';
import 'package:mobile/features/accounts/presentation/role_picker_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.navProfile)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              session.activeRole == AppRole.trainer
                  ? ref
                            .watch(staffProfileProvider(session.accountId))
                            .asData
                            ?.value
                            .displayName ??
                        session.displayName
                  : session.displayName,
            ),
            if (session.choices.length > 1)
              ListTile(
                leading: const Icon(Icons.swap_horiz),
                title: Text(l10n.profileSwitchRole),
                subtitle: Text(switch (choiceLabels(
                  l10n,
                  session,
                  session.activeChoice,
                )) {
                  (:final name, :final role) => '$role · $name',
                }),
                trailing: const Icon(Icons.chevron_right),
                onTap: controller.openRolePicker,
              ),
            if (session.activeRole == AppRole.trainer) ...[
              ListTile(
                leading: const Icon(Icons.edit_outlined),
                title: Text(l10n.staffProfileEditTitle),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/trainer/profile/edit'),
              ),
              ListTile(
                leading: const Icon(Icons.schedule),
                title: Text(l10n.navAvailability),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/trainer/profile/availability'),
              ),
            ],
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
