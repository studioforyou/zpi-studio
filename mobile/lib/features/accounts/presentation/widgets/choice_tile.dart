import 'package:flutter/material.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';
import 'package:mobile/features/accounts/presentation/widgets/shrink_to_fit_text.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The first name and the role shown for [choice]. Profiles are used by one
/// person or one family, so last names are left out.
({String name, String role}) _choiceLabels(
  AppLocalizations l10n,
  Session session,
  SessionChoice choice,
) {
  final profile = choice.profile;
  return (
    name: _firstName(profile?.displayName ?? session.displayName),
    role: switch (choice.role) {
      AppRole.trainer => l10n.roleTrainer,
      AppRole.client when profile != null && profile.isChild => l10n.roleChild,
      AppRole.client => l10n.roleClient,
    },
  );
}

String _firstName(String displayName) =>
    displayName.trim().split(RegExp(r'\s+')).first;

/// The colour of [choice]. It follows the position in [Session.choices], so a
/// person keeps their colour on every screen.
Color _colorFor(Session session, SessionChoice choice) {
  if (choice.role == AppRole.trainer) {
    return ChoiceTileStyle.trainerColor;
  }
  const colors = ChoiceTileStyle.clientColors;
  final index = session.choices.indexOf(choice);
  return colors[(index < 0 ? 0 : index) % colors.length];
}

/// One square tile with the first letter of the name, and the name and the
/// role below it. Everything scales with [size], the side of the square. Used
/// by the role picker and by the profile tab; its look is set in
/// [ChoiceTileStyle].
class ChoiceTile extends StatelessWidget {
  const ChoiceTile({
    super.key,
    required this.size,
    required this.labels,
    required this.color,
    this.onTap,
    this.labelScale = 1,
    this.labelGap = ChoiceTileStyle.labelGap,
    this.labelLineHeight,
  });

  /// A tile for [choice] of [session], with its labels and colour.
  factory ChoiceTile.forChoice({
    Key? key,
    required AppLocalizations l10n,
    required Session session,
    required SessionChoice choice,
    required double size,
    VoidCallback? onTap,
    double labelScale = 1,
    double labelGap = ChoiceTileStyle.labelGap,
    double? labelLineHeight,
  }) => ChoiceTile(
    key: key,
    size: size,
    labels: _choiceLabels(l10n, session, choice),
    color: _colorFor(session, choice),
    onTap: onTap,
    labelScale: labelScale,
    labelGap: labelGap,
    labelLineHeight: labelLineHeight,
  );

  final double size;
  final ({String name, String role}) labels;
  final Color color;

  /// `null` makes the tile a plain picture that does not react to taps.
  final VoidCallback? onTap;

  /// Multiplies the size of the name and the role, e.g. to keep them
  /// readable under small squares.
  final double labelScale;

  /// Gap between the square and the name, as a fraction of the side.
  final double labelGap;

  /// Line height of the name and the role as a multiple of their text size;
  /// smaller brings them closer together. `null` keeps the font's own.
  final double? labelLineHeight;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(size * ChoiceTileStyle.cornerRadius);
    final nameFontSize = size * ChoiceTileStyle.nameSize * labelScale;
    final letterColor =
        ThemeData.estimateBrightnessForColor(color) == Brightness.light
        ? ChoiceTileStyle.darkLetterColor
        : ChoiceTileStyle.lightLetterColor;

    return SizedBox(
      width: size,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Column(
          children: [
            Container(
              width: size,
              height: size,
              alignment: Alignment.center,
              decoration: BoxDecoration(color: color, borderRadius: radius),
              child: Text(
                labels.name.characters.firstOrNull ?? '',
                style: TextStyle(
                  fontSize: size * ChoiceTileStyle.letterSize,
                  fontWeight: ChoiceTileStyle.letterWeight,
                  color: letterColor,
                ),
              ),
            ),
            SizedBox(height: size * labelGap),
            ShrinkToFitText(
              labels.name,
              textAlign: TextAlign.center,
              minFontSize: nameFontSize * ChoiceTileStyle.nameMinScale,
              style: TextStyle(
                fontSize: nameFontSize,
                fontWeight: ChoiceTileStyle.nameWeight,
                color: ChoiceTileStyle.nameColor,
                height: labelLineHeight,
                leadingDistribution: TextLeadingDistribution.even,
              ),
            ),
            Text(
              labels.role,
              textAlign: TextAlign.center,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: size * ChoiceTileStyle.roleSize * labelScale,
                color: ChoiceTileStyle.roleColor,
                height: labelLineHeight,
                leadingDistribution: TextLeadingDistribution.even,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
