import 'package:flutter/material.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Color scheduleProfileColor(Session session, Profile profile) {
  const colors = ChoiceTileStyle.clientColors;
  final index = session.profiles.indexWhere((p) => p.id == profile.id);
  return colors[(index < 0 ? 0 : index) % colors.length];
}

Color _letterColor(Color color) =>
    ThemeData.estimateBrightnessForColor(color) == Brightness.light
    ? ChoiceTileStyle.darkLetterColor
    : ChoiceTileStyle.lightLetterColor;

String _firstName(Profile profile) =>
    profile.displayName.trim().split(RegExp(r'\s+')).first;

/// The same initials and colours as the profile picker, in a single short row.
class ScheduleProfilePicker extends StatelessWidget {
  const ScheduleProfilePicker({
    super.key,
    required this.session,
    required this.allSelected,
    required this.onProfile,
    this.onAll,
  });
  final Session session;
  final bool allSelected;
  final ValueChanged<Profile> onProfile;
  final VoidCallback? onAll;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      child: Row(
        children: [
          if (onAll != null)
            _Choice(
              label: l10n.scheduleAllProfiles,
              tooltip: l10n.scheduleAllProfilesHint,
              selected: allSelected,
              color: Theme.of(context).colorScheme.primaryContainer,
              onTap: onAll!,
              child: const Icon(Icons.groups_outlined, size: 19),
            ),
          for (final profile in session.profiles)
            _Choice(
              key: ValueKey('schedule-profile-${profile.id}'),
              label:
                  session.profiles
                          .where((p) => _firstName(p) == _firstName(profile))
                          .length >
                      1
                  ? profile.displayName
                  : _firstName(profile),
              tooltip: l10n.scheduleProfileLabel(profile.displayName),
              selected: !allSelected && session.activeProfileId == profile.id,
              color: scheduleProfileColor(session, profile),
              onTap: () => onProfile(profile),
              child: Text(profile.displayName.characters.firstOrNull ?? ''),
            ),
        ],
      ),
    );
  }
}

class _Choice extends StatelessWidget {
  const _Choice({
    super.key,
    required this.label,
    required this.tooltip,
    required this.selected,
    required this.color,
    required this.onTap,
    required this.child,
  });
  final String label, tooltip;
  final bool selected;
  final Color color;
  final VoidCallback onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Semantics(
      selected: selected,
      button: true,
      label: tooltip,
      child: Tooltip(
        message: tooltip,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(24),
          child: Container(
            constraints: const BoxConstraints(minHeight: 44),
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  padding: const EdgeInsets.all(2),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected ? scheme.primary : Colors.transparent,
                      width: 2,
                    ),
                  ),
                  child: CircleAvatar(
                    radius: 13,
                    backgroundColor: color,
                    foregroundColor: _letterColor(color),
                    child: DefaultTextStyle.merge(
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                      child: child,
                    ),
                  ),
                ),
                const SizedBox(width: 5),
                Text(
                  label,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
                    color: selected ? scheme.primary : scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class ScheduleProfileBadge extends StatelessWidget {
  const ScheduleProfileBadge({
    super.key,
    required this.session,
    required this.profile,
  });
  final Session session;
  final Profile profile;
  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      CircleAvatar(
        radius: 9,
        backgroundColor: scheduleProfileColor(session, profile),
        foregroundColor: _letterColor(scheduleProfileColor(session, profile)),
        child: Text(
          profile.displayName.characters.firstOrNull ?? '',
          style: const TextStyle(fontSize: 10),
        ),
      ),
      const SizedBox(width: 5),
      Flexible(
        child: Text(
          profile.displayName,
          style: Theme.of(context).textTheme.labelSmall,
        ),
      ),
    ],
  );
}
