import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/intro/login_intro.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/presentation/widgets/choice_tile.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Shown after signing in, and from the profile tab, when the account can use
/// the app in more than one way: as a client for itself or for a child, or as
/// a trainer. The tiles themselves are [ChoiceTile]s; the layout is set in
/// [RolePickerStyle].
class RolePickerScreen extends ConsumerWidget {
  const RolePickerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.read(authControllerProvider.notifier);

    return Scaffold(
      backgroundColor: RolePickerStyle.backgroundColor,
      body: SafeArea(
        child: switch (ref.watch(authControllerProvider)) {
          AuthRestoring() || Unauthenticated() => const SizedBox(),
          Authenticated(:final session, :final switchingRole) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Pinned to the top; the tiles scroll below it.
              Padding(
                padding: const EdgeInsets.fromLTRB(
                  RolePickerStyle.sideMargin,
                  RolePickerStyle.titleTopMargin,
                  RolePickerStyle.sideMargin,
                  RolePickerStyle.titleBottomMargin,
                ),
                child: Text(
                  l10n.rolePickerTitle,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: RolePickerStyle.titleColor,
                    fontWeight: RolePickerStyle.titleWeight,
                  ),
                ),
              ),
              Expanded(
                child: Center(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(
                      RolePickerStyle.sideMargin,
                      0,
                      RolePickerStyle.sideMargin,
                      RolePickerStyle.bottomMargin,
                    ),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        final rowWidth =
                            constraints.maxWidth < RolePickerStyle.maxRowWidth
                            ? constraints.maxWidth
                            : RolePickerStyle.maxRowWidth;
                        final tileSize =
                            (rowWidth -
                                RolePickerStyle.tileSpacing *
                                    (RolePickerStyle.tilesPerRow - 1)) /
                            RolePickerStyle.tilesPerRow;
                        return SizedBox(
                          width: rowWidth,
                          child: Wrap(
                            alignment: WrapAlignment.center,
                            spacing: RolePickerStyle.tileSpacing,
                            runSpacing: RolePickerStyle.tileSpacing,
                            children: [
                              for (final choice in session.choices)
                                ChoiceTile.forChoice(
                                  l10n: l10n,
                                  session: session,
                                  choice: choice,
                                  size: tileSize,
                                  // From the profile tab the switch
                                  // happens behind the logo intro.
                                  onTap: switchingRole
                                      ? () => ref
                                            .read(
                                              profileSwitchProvider.notifier,
                                            )
                                            .request(choice)
                                      : () => controller.choose(choice),
                                ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
            ],
          ),
        },
      ),
    );
  }
}
