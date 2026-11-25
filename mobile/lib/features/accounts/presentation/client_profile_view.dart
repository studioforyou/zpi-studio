import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/shell/client_shell.dart';
import 'package:mobile/app/theme/client/theme_mode.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/documents/presentation/documents_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Profil of the client app (#281): who is signed in, their details and
/// documents, the theme, the family setting and the way out.
class ClientProfileView extends ConsumerWidget {
  const ClientProfileView({super.key, required this.session});

  final Session session;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final profileId = session.activeProfileId;
    final controller = ref.read(authControllerProvider.notifier);
    return Scaffold(
      appBar: ClientTabHeader(title: l10n.navProfile),
      body: ListView(
        padding: EdgeInsets.only(bottom: sp.xl),
        children: [
          _ProfileBanner(session: session),
          if (profileId != null) ...[
            AppRow(
              title: l10n.personalDetailsEditTitle,
              topLine: false,
              bottomLine: true,
              onTap: () => context.push('/client/profile/edit'),
            ),
            AppRow(
              title: l10n.documentsTitle,
              topLine: false,
              bottomLine: true,
              onTap: () => context.push(documentsRoute),
            ),
          ],
          const _ThemeRow(),
          if (profileId != null && session.profiles.length > 1)
            _RelatedProfilesRow(
              preferenceKey: (
                accountId: session.accountId,
                profileId: profileId,
              ),
            ),
          Padding(
            padding: EdgeInsets.fromLTRB(sp.page, sp.xl - 2, sp.page, 0),
            child: AppButtonPair(
              first: session.choices.length > 1
                  ? AppButton(
                      label: l10n.profileSwitchProfile,
                      icon: Icons.swap_horiz,
                      onPressed: controller.openRolePicker,
                    )
                  : _logout(context, ref),
              second: session.choices.length > 1 ? _logout(context, ref) : null,
            ),
          ),
        ],
      ),
    );
  }

  AppButton _logout(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return AppButton(
      label: l10n.logoutButton,
      icon: Icons.logout,
      onPressed: () async {
        final confirmed = await showAppSheet<bool>(
          context,
          builder: (sheetContext) => AppSheet(
            title: l10n.profileLogoutQuestion,
            titleSmall: true,
            showClose: false,
            children: [
              SizedBox(height: sheetContext.ct.spacing.l + 2),
              AppButtonPair(
                first: AppButton(
                  label: l10n.commonCancel,
                  onPressed: () => Navigator.of(sheetContext).pop(false),
                ),
                second: AppButton(
                  label: l10n.logoutButton,
                  onPressed: () => Navigator.of(sheetContext).pop(true),
                ),
              ),
            ],
          ),
        );
        if (confirmed == true) {
          await ref.read(authControllerProvider.notifier).logout();
        }
      },
    );
  }
}

/// The role, the name and, on the right, the initials on a band that fades
/// out to the left.
class _ProfileBanner extends StatelessWidget {
  const _ProfileBanner({required this.session});

  final Session session;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final profile = session.activeChoice.profile;
    final name = profile?.displayName ?? session.displayName;
    final band = ct.sizes.profileBanner;
    return Container(
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Stack(
        children: [
          Positioned(
            top: 0,
            right: 0,
            bottom: 0,
            width: band,
            child: ExcludeSemantics(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.centerRight,
                    end: Alignment.centerLeft,
                    colors: [
                      c.profileBanner,
                      c.profileBanner.withValues(alpha: 0.55),
                      c.profileBanner.withValues(alpha: 0),
                    ],
                    stops: const [0, 0.5, 1],
                  ),
                ),
                child: Center(
                  child: Text(
                    SquareAvatar.initialsOf(name),
                    style: ct.text.profileMonogram.copyWith(
                      color: c.onProfileBanner,
                    ),
                  ),
                ),
              ),
            ),
          ),
          Padding(
            // The name may run a little into the band, not under the initials.
            padding: EdgeInsets.fromLTRB(
              sp.page,
              sp.page,
              sp.page + band / 2,
              sp.xl,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  profile != null && profile.isChild
                      ? l10n.roleChild
                      : l10n.roleClient,
                  style: ct.text.lineValue,
                ),
                SizedBox(height: sp.m),
                Text(name, style: ct.text.profileName),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ThemeRow extends ConsumerWidget {
  const _ThemeRow();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return _SettingRow(
      title: l10n.profileTheme,
      control: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.light_mode_outlined, color: c.muted, size: ct.sizes.icon),
          SizedBox(width: sp.s),
          AppSwitch(
            value: dark,
            semanticLabel: l10n.profileThemeDark,
            onChanged: (value) => ref
                .read(themeModeProvider.notifier)
                .set(value ? ThemeMode.dark : ThemeMode.light),
          ),
          SizedBox(width: sp.s),
          Icon(Icons.dark_mode_outlined, color: c.muted, size: ct.sizes.icon),
        ],
      ),
    );
  }
}

class _RelatedProfilesRow extends ConsumerWidget {
  const _RelatedProfilesRow({required this.preferenceKey});

  final ProfileViewKey preferenceKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final preference = ref.watch(profileViewPreferencesProvider(preferenceKey));
    return _SettingRow(
      title: l10n.profileShowRelatedProfiles,
      hint: l10n.profileShowRelatedProfilesHint,
      control: AppSwitch(
        value: preference.value ?? false,
        semanticLabel: l10n.profileShowRelatedProfiles,
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
      ),
    );
  }
}

/// A settings row: title, optional hint and a control on the right.
class _SettingRow extends StatelessWidget {
  const _SettingRow({required this.title, this.hint, required this.control});

  final String title;
  final String? hint;
  final Widget control;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    // Only the text gets the row padding: the control already has the height
    // of a tap target.
    return Container(
      constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
      padding: EdgeInsets.symmetric(horizontal: sp.page),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Row(
        children: [
          Expanded(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: sp.rowVertical),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: ct.text.rowTitle),
                  if (hint != null) ...[
                    SizedBox(height: sp.xxs),
                    Text(hint!, style: ct.text.meta),
                  ],
                ],
              ),
            ),
          ),
          SizedBox(width: sp.m),
          control,
        ],
      ),
    );
  }
}
