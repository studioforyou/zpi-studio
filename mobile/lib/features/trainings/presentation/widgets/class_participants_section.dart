import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Who else comes to a class the participant booked, in the class sheet.
class ClassParticipantsSection extends ConsumerWidget {
  const ClassParticipantsSection({required this.query, super.key});

  final ClassQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final provider = classParticipantsProvider(query);
    final participants = ref.watch(provider);
    final count = participants.value?.length;
    return Container(
      margin: EdgeInsets.only(top: sp.m),
      padding: EdgeInsets.only(top: sp.m),
      decoration: BoxDecoration(border: Border(top: ct.hairline)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            header: true,
            child: Text(
              count == null || count == 0
                  ? l10n.classesParticipants
                  : '${l10n.classesParticipants} · $count',
              style: ct.text.lineLabel,
            ),
          ),
          SizedBox(height: sp.xs),
          participants.when(
            skipLoadingOnRefresh: false,
            loading: () => Padding(
              padding: EdgeInsets.symmetric(vertical: sp.s),
              child: SizedBox.square(
                dimension: ct.sizes.icon,
                child: const CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
            error: (_, _) => Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.classesParticipantsError,
                    style: ct.text.meta.copyWith(color: c.muted),
                  ),
                ),
                AppButton.ghost(
                  label: l10n.scheduleRetryButton,
                  onPressed: () => ref.invalidate(provider),
                ),
              ],
            ),
            data: (list) => list.isEmpty
                ? Text(
                    l10n.classesParticipantsEmpty,
                    style: ct.text.meta.copyWith(color: c.muted),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (final participant in list)
                        Padding(
                          padding: EdgeInsets.symmetric(vertical: sp.xs),
                          child: Row(
                            children: [
                              SquareAvatar(
                                initials: SquareAvatar.initialsOf(
                                  participant.displayName,
                                ),
                                background: participant.isSelf
                                    ? c.highlight
                                    : c.surface,
                                foreground: participant.isSelf
                                    ? c.background
                                    : c.muted,
                                size: ct.sizes.badge,
                                style: ct.text.badge,
                              ),
                              SizedBox(width: sp.s),
                              Expanded(
                                child: Text(
                                  participant.isSelf
                                      ? l10n.classesParticipantSelf(
                                          participant.displayName,
                                        )
                                      : participant.displayName,
                                  style: ct.text.body,
                                ),
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}
