import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/staff_profile_provider.dart';
import 'package:mobile/features/accounts/presentation/widgets/choice_tile.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';
import 'package:mobile/features/accounts/presentation/widgets/shrink_to_fit_text.dart';
import 'package:mobile/features/accounts/presentation/widgets/text_action.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The top of the profile tab: the active profile's tile (a picture, not a
/// button) and, to its right, the full name, "Zmień profil" (back to the role
/// picker, only when there is something to choose) and "Wyloguj się". A
/// line across the screen separates it from the rest of the tab.
///
/// The look is set in [ProfileHeaderStyle] and [ChoiceTileStyle].
class ProfileHeader extends ConsumerWidget {
  const ProfileHeader({super.key, required this.session});

  final Session session;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);
    final active = session.activeChoice;

    // A trainer shows the name from their staff profile, a client the name
    // of the customer profile in use (e.g. the child).
    final fullName = switch (active.profile) {
      final profile? => profile.displayName,
      null when active.role == AppRole.trainer =>
        ref
                .watch(staffProfileProvider(session.accountId))
                .asData
                ?.value
                .displayName ??
            session.displayName,
      null => session.displayName,
    };

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            ProfileHeaderStyle.leftMargin,
            ProfileHeaderStyle.topMargin,
            ProfileHeaderStyle.rightMargin,
            0,
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ChoiceTile.forChoice(
                l10n: l10n,
                session: session,
                choice: active,
                size: ProfileHeaderStyle.tileSize,
                labelScale: ProfileHeaderStyle.labelScale,
                labelLineHeight: ProfileHeaderStyle.labelLineHeight,
                labelGap: ProfileHeaderStyle.labelGap,
              ),
              const SizedBox(width: ProfileHeaderStyle.textGap),
              // At least as tall as the square, so the text is centred on it;
              // taller only when large system text needs the room.
              Expanded(
                child: Container(
                  constraints: const BoxConstraints(
                    minHeight: ProfileHeaderStyle.tileSize,
                  ),
                  alignment: Alignment.centerLeft,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ShrinkToFitText(
                        fullName.toUpperCase(),
                        minFontSize: ProfileHeaderStyle.nameMinSize,
                        maxLines: ProfileHeaderStyle.nameMaxLines,
                        style: const TextStyle(
                          fontSize: ProfileHeaderStyle.nameSize,
                          fontWeight: ProfileHeaderStyle.textWeight,
                          letterSpacing: ProfileHeaderStyle.textLetterSpacing,
                          color: ProfileHeaderStyle.nameColor,
                        ),
                      ),
                      const SizedBox(height: ProfileHeaderStyle.nameActionsGap),
                      if (session.choices.length > 1) ...[
                        TextAction(
                          label: l10n.profileSwitchProfile,
                          onTap: controller.openRolePicker,
                        ),
                        const SizedBox(height: ProfileHeaderStyle.actionsGap),
                      ],
                      TextAction(
                        label: l10n.logoutButton,
                        onTap: controller.logout,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: ProfileHeaderStyle.dividerMargin),
        const Divider(
          height: ProfileHeaderStyle.dividerThickness,
          thickness: ProfileHeaderStyle.dividerThickness,
          color: ProfileHeaderStyle.dividerColor,
          indent: ProfileHeaderStyle.dividerSideMargin,
          endIndent: ProfileHeaderStyle.dividerSideMargin,
        ),
        const SizedBox(height: ProfileHeaderStyle.dividerMargin),
      ],
    );
  }
}
