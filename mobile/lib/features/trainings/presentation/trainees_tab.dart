import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Opens the search of participants to start working with.
const newCooperationLocation = '/trainer/trainings/new-cooperation';

/// Participants the signed-in trainer works with.
class TraineesTab extends ConsumerWidget {
  const TraineesTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    if (session == null) {
      return const SizedBox();
    }
    final provider = ownTraineesProvider(session.accountId);

    return ref
        .watch(provider)
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _LoadError(
            message: traineesErrorMessage(error, l10n, loading: true),
            onRetry: () => ref.invalidate(provider),
          ),
          data: (trainees) => RefreshIndicator(
            onRefresh: () => ref.refresh(provider.future),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                FilledButton.icon(
                  onPressed: () => context.push(newCooperationLocation),
                  icon: const Icon(Icons.person_add_alt_1),
                  label: Text(l10n.traineesNewCooperation),
                ),
                const SizedBox(height: 16),
                if (trainees.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 32),
                    child: Text(
                      l10n.traineesEmpty,
                      textAlign: TextAlign.center,
                    ),
                  ),
                for (final trainee in trainees)
                  _TraineeCard(
                    trainee,
                    onEnd: () => _endCooperation(context, ref, trainee),
                  ),
              ],
            ),
          ),
        );
  }

  Future<void> _endCooperation(
    BuildContext context,
    WidgetRef ref,
    Trainee trainee,
  ) async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.traineesEndTitle),
        content: Text(l10n.traineesEndText(trainee.displayName)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.traineesCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.traineesEndConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(traineesApiProvider).unassign(trainee.customerId);
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.traineesEnded(trainee.displayName))),
      );
    } catch (e) {
      messenger.showSnackBar(
        SnackBar(content: Text(traineesErrorMessage(e, l10n))),
      );
    }
    if (context.mounted) {
      ref.invalidate(ownTraineesProvider);
    }
  }
}

class _TraineeCard extends StatelessWidget {
  const _TraineeCard(this.trainee, {required this.onEnd});

  final Trainee trainee;
  final VoidCallback onEnd;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final details = [
      if (trainee.isChild) l10n.traineesChild,
      l10n.traineesSince(
        DateFormat('d MMMM y', 'pl').format(trainee.assignedAt),
      ),
      if (!trainee.isActive) l10n.traineesInactive,
    ];

    return Card(
      child: ListTile(
        leading: CircleAvatar(child: Text(_initials(trainee.displayName))),
        title: Text(trainee.displayName),
        subtitle: Text(details.join(' · ')),
        trailing: PopupMenuButton<void>(
          tooltip: l10n.traineesActions,
          itemBuilder: (_) => [
            PopupMenuItem(onTap: onEnd, child: Text(l10n.traineesEndConfirm)),
          ],
        ),
      ),
    );
  }

  static String _initials(String name) => name
      .split(' ')
      .where((part) => part.isNotEmpty)
      .take(2)
      .map((part) => part[0].toUpperCase())
      .join();
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(message, textAlign: TextAlign.center),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: Text(AppLocalizations.of(context).traineesRetry),
            ),
          ],
        ),
      ),
    );
  }
}

/// A message for a failed trainees request, by its HTTP status.
String traineesErrorMessage(
  Object error,
  AppLocalizations l10n, {
  bool loading = false,
}) {
  if (error is ApiException) {
    return switch (error.statusCode) {
      null => l10n.traineesNetworkError,
      401 || 403 => l10n.traineesAccessError,
      404 when loading => l10n.traineesNoStaffProfile,
      409 => l10n.traineesAlreadyAssigned,
      422 => l10n.traineesNotAvailable,
      _ => loading ? l10n.traineesLoadError : l10n.traineesSaveError,
    };
  }
  return loading ? l10n.traineesLoadError : l10n.traineesSaveError;
}
