import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/theme/brand_colors.dart';
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

  /// Tile colours of client choices, used in turn; the trainer tile always
  /// gets the darkest one.
  static const _clientGradients = [
    (BrandColors.teal300, BrandColors.teal500),
    (BrandColors.teal100, BrandColors.teal300),
    (BrandColors.teal500, BrandColors.teal700),
  ];
  static const _trainerGradient = (BrandColors.teal700, BrandColors.teal900);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);

    return Scaffold(
      backgroundColor: BrandColors.paper,
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
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      color: BrandColors.teal900,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 28),
                  SizedBox(
                    // Two tiles per row.
                    width: 2 * _ChoiceTile.width + 26,
                    child: Wrap(
                      alignment: WrapAlignment.center,
                      spacing: 26,
                      runSpacing: 22,
                      children: [
                        for (final (i, choice) in session.choices.indexed)
                          _ChoiceTile(
                            labels: choiceLabels(l10n, session, choice),
                            gradient: choice.role == AppRole.trainer
                                ? _trainerGradient
                                : _clientGradients[i % _clientGradients.length],
                            onTap: () => controller.choose(choice),
                          ),
                      ],
                    ),
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
  const _ChoiceTile({
    required this.labels,
    required this.gradient,
    required this.onTap,
  });

  static const width = 120.0;
  static const _avatarSize = 112.0;
  static const _radius = BorderRadius.all(Radius.circular(20));

  final ({String name, String role}) labels;
  final (Color, Color) gradient;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final (start, end) = gradient;
    // The lightest tile needs dark text.
    final initialColor = start == BrandColors.teal100
        ? BrandColors.teal900
        : Colors.white;

    return SizedBox(
      width: width,
      child: InkWell(
        onTap: onTap,
        borderRadius: _radius,
        child: Column(
          children: [
            Container(
              width: _avatarSize,
              height: _avatarSize,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                borderRadius: _radius,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [start, end],
                ),
              ),
              child: Text(
                labels.name.characters.firstOrNull ?? '',
                style: TextStyle(
                  fontSize: 40,
                  fontWeight: FontWeight.w600,
                  color: initialColor,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              labels.name,
              textAlign: TextAlign.center,
              style: textTheme.bodyMedium?.copyWith(color: BrandColors.teal900),
            ),
            Text(
              labels.role,
              textAlign: TextAlign.center,
              style: textTheme.bodySmall?.copyWith(color: BrandColors.teal500),
            ),
          ],
        ),
      ),
    );
  }
}
