import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ClassParticipantsSection extends ConsumerWidget {
  const ClassParticipantsSection({
    required this.query,
    required this.hasReservation,
    super.key,
  });

  final ClassQuery query;
  final bool hasReservation;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = classParticipantsProvider(query);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 20),
        const Divider(),
        const SizedBox(height: 16),
        Text(l10n.classesParticipants, style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        if (!hasReservation)
          Text(
            l10n.classesParticipantsAfterBooking,
            style: theme.textTheme.bodySmall,
          )
        else
          ref
              .watch(provider)
              .when(
                skipLoadingOnRefresh: false,
                loading: () => const Padding(
                  padding: EdgeInsets.all(12),
                  child: Center(
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                ),
                error: (_, _) => Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.classesParticipantsError),
                    TextButton.icon(
                      onPressed: () => ref.invalidate(provider),
                      icon: const Icon(Icons.refresh),
                      label: Text(l10n.scheduleRetryButton),
                    ),
                  ],
                ),
                data: (participants) => participants.isEmpty
                    ? Text(l10n.classesParticipantsEmpty)
                    : Column(
                        children: [
                          for (final participant in participants)
                            ListTile(
                              contentPadding: EdgeInsets.zero,
                              dense: true,
                              leading: CircleAvatar(
                                radius: 16,
                                backgroundColor: participant.isSelf
                                    ? theme.colorScheme.primaryContainer
                                    : theme.colorScheme.surfaceContainerHighest,
                                child: Icon(
                                  participant.isSelf
                                      ? Icons.check
                                      : Icons.person_outline,
                                  size: 18,
                                ),
                              ),
                              title: Text(
                                participant.isSelf
                                    ? l10n.classesParticipantSelf(
                                        participant.displayName,
                                      )
                                    : participant.displayName,
                              ),
                            ),
                        ],
                      ),
              ),
      ],
    );
  }
}
