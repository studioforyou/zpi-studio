import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/presentation/client_profile_view.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_header.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    if (auth case Authenticated(
      :final session,
    ) when session.activeRole == AppRole.client) {
      return ClientProfileView(session: session);
    }

    return Scaffold(
      appBar: AppBar(title: Text(l10n.navProfile)),
      body: switch (auth) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ListView(
          // The header sets its own margins.
          padding: const EdgeInsets.only(bottom: 16),
          children: [
            // Full width, so its line can run from edge to edge.
            ProfileHeader(session: session),
            if (session.activeRole == AppRole.trainer)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    ListTile(
                      leading: const Icon(Icons.edit_outlined),
                      title: Text(l10n.personalDetailsEditTitle),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push('/trainer/profile/edit'),
                    ),
                    ListTile(
                      leading: const Icon(Icons.schedule),
                      title: Text(l10n.navAvailability),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () =>
                          context.push('/trainer/profile/availability'),
                    ),
                  ],
                ),
              ),
          ],
        ),
      },
    );
  }
}
