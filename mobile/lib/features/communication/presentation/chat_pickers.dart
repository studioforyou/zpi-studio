import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// One person to pick in [_PersonPicker].
class _Candidate {
  const _Candidate({
    required this.id,
    required this.name,
    this.details,
    this.conversationId,
  });

  final String id;
  final String name;
  final String? details;

  /// The existing conversation with the person, if any.
  final String? conversationId;
}

/// Lets the trainer pick one of their clients to write to: trainees and
/// participants of their groups and classes. Pops with the id of the
/// conversation to open.
class TraineePickerScreen extends ConsumerWidget {
  const TraineePickerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final accountId = _accountId(ref);
    if (accountId == null) {
      return const SizedBox();
    }
    final provider = messageableClientsProvider(accountId);
    return _PersonPicker(
      title: l10n.messagesNewToTrainee,
      searchHint: l10n.messagesSearchTrainee,
      emptyText: l10n.messagesNoTrainees,
      candidates: ref
          .watch(provider)
          .whenData(
            (clients) => [
              for (final client in clients)
                _Candidate(
                  id: client.customerId,
                  name: client.displayName,
                  details: _clientDetails(client, l10n),
                  conversationId: client.conversationId,
                ),
            ],
          ),
      onRetry: () => ref.invalidate(provider),
      open: (candidate) async =>
          (await ref
                  .read(conversationsApiProvider)
                  .startWithTrainee(candidate.id))
              .id,
    );
  }
}

/// Lets the client pick a trainer to write to. Pops with the id of the
/// conversation to open.
class TrainerPickerScreen extends ConsumerWidget {
  const TrainerPickerScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final accountId = _accountId(ref);
    if (accountId == null) {
      return const SizedBox();
    }
    final provider = messageableTrainersProvider(accountId);
    return _PersonPicker(
      title: l10n.messagesWriteToTrainer,
      searchHint: l10n.messagesSearchTrainer,
      emptyText: l10n.messagesNoTrainers,
      candidates: ref
          .watch(provider)
          .whenData(
            (trainers) => [
              for (final trainer in trainers)
                _Candidate(
                  id: trainer.staffId,
                  name: trainer.displayName,
                  details: l10n.chatTrainer,
                  conversationId: trainer.conversationId,
                ),
            ],
          ),
      onRetry: () => ref.invalidate(provider),
      open: (candidate) async =>
          (await ref
                  .read(conversationsApiProvider)
                  .startWithTrainer(candidate.id))
              .id,
    );
  }
}

/// What links the client with the trainer, e.g. `Podopieczny · Stretching`.
String? _clientDetails(MessageableClient client, AppLocalizations l10n) {
  final parts = [
    if (client.isChild) l10n.traineesChild,
    if (client.isTrainee) l10n.messagesClientTrainee,
    ...client.classes,
  ];
  return parts.isEmpty ? null : parts.join(' · ');
}

String? _accountId(WidgetRef ref) =>
    switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session.accountId,
      _ => null,
    };

class _PersonPicker extends StatefulWidget {
  const _PersonPicker({
    required this.title,
    required this.searchHint,
    required this.emptyText,
    required this.candidates,
    required this.onRetry,
    required this.open,
  });

  final String title;
  final String searchHint;
  final String emptyText;
  final AsyncValue<List<_Candidate>> candidates;
  final VoidCallback onRetry;

  /// Opens (or creates) the conversation with the person; returns its id.
  final Future<String> Function(_Candidate) open;

  @override
  State<_PersonPicker> createState() => _PersonPickerState();
}

class _PersonPickerState extends State<_PersonPicker> {
  String _query = '';
  String? _openingId;

  Future<void> _pick(_Candidate candidate) async {
    final existing = candidate.conversationId;
    if (existing != null) {
      context.pop(existing);
      return;
    }
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _openingId = candidate.id);
    try {
      final id = await widget.open(candidate);
      if (mounted) {
        context.pop(id);
      }
    } catch (e) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            e is ApiException && e.statusCode == 422
                ? l10n.messagesNoAccount
                : messagesErrorMessage(e, l10n),
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _openingId = null);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: SearchBar(
              hintText: widget.searchHint,
              leading: const Icon(Icons.search),
              elevation: const WidgetStatePropertyAll(0),
              onChanged: (value) =>
                  setState(() => _query = value.trim().toLowerCase()),
            ),
          ),
          Expanded(
            child: widget.candidates.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (error, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    spacing: 16,
                    children: [
                      Text(
                        messagesErrorMessage(error, l10n, loading: true),
                        textAlign: TextAlign.center,
                      ),
                      OutlinedButton.icon(
                        onPressed: widget.onRetry,
                        icon: const Icon(Icons.refresh),
                        label: Text(l10n.messagesRetry),
                      ),
                    ],
                  ),
                ),
              ),
              data: (candidates) {
                if (candidates.isEmpty) {
                  return _CenteredText(widget.emptyText);
                }
                final shown = [
                  for (final candidate in candidates)
                    if (candidate.name.toLowerCase().contains(_query))
                      candidate,
                ];
                if (shown.isEmpty) {
                  return _CenteredText(l10n.messagesNoMatches);
                }
                return ListView(
                  children: [
                    for (final candidate in shown)
                      ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 4,
                        ),
                        leading: PersonAvatar(
                          name: candidate.name,
                          seed: candidate.id,
                        ),
                        title: Text(candidate.name),
                        subtitle: candidate.details == null
                            ? null
                            : Text(candidate.details!),
                        enabled: _openingId == null,
                        trailing: _openingId == candidate.id
                            ? const SizedBox.square(
                                dimension: 24,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : null,
                        onTap: () => _pick(candidate),
                      ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _CenteredText extends StatelessWidget {
  const _CenteredText(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Text(text, textAlign: TextAlign.center),
    ),
  );
}
