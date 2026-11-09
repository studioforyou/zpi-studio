import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_header.dart';
import 'package:mobile/features/documents/presentation/documents_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.navProfile)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => ListView(
          // The header sets its own margins.
          padding: const EdgeInsets.only(bottom: 16),
          children: [
            // Full width, so its line can run from edge to edge.
            ProfileHeader(session: session),
            if (session.activeRole == AppRole.client &&
                session.activeProfileId != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    ListTile(
                      leading: const Icon(Icons.edit_outlined),
                      title: Text(l10n.personalDetailsEditTitle),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push('/client/profile/edit'),
                    ),
                    ListTile(
                      leading: const Icon(Icons.description_outlined),
                      title: Text(l10n.documentsTitle),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push(documentsRoute),
                    ),
                    if (session.profiles.length > 1)
                      _RelatedProfilesSetting(
                        preferenceKey: (
                          accountId: session.accountId,
                          profileId: session.activeProfileId!,
                        ),
                      ),
                  ],
                ),
              ),
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

class _RelatedProfilesSetting extends ConsumerWidget {
  const _RelatedProfilesSetting({required this.preferenceKey});
  final ProfileViewKey preferenceKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final preference = ref.watch(profileViewPreferencesProvider(preferenceKey));
    return SwitchListTile.adaptive(
      secondary: const Icon(Icons.family_restroom_outlined),
      title: Text(l10n.profileShowRelatedProfiles),
      subtitle: Text(l10n.profileShowRelatedProfilesHint),
      value: preference.value ?? false,
      onChanged: preference.isLoading
          ? null
          : (enabled) async {
              try {
                await ref
                    .read(
                      profileViewPreferencesProvider(preferenceKey).notifier,
                    )
                    .setShowRelated(enabled);
              } catch (_) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text(l10n.profileViewSaveError)),
                  );
                }
              }
            },
    );
  }
}
