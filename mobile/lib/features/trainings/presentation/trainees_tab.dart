import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Opens the search of participants to start working with.
const newCooperationLocation = '/trainer/trainings/new-cooperation';

/// Opens the form that creates a client account for a new trainee.
const newClientAccountLocation = '/trainer/trainings/new-client';

/// Participants the signed-in trainer works with.
class TraineesTab extends ConsumerStatefulWidget {
  const TraineesTab({super.key});

  @override
  ConsumerState<TraineesTab> createState() => _TraineesTabState();
}

class _TraineesTabState extends ConsumerState<TraineesTab>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final scope = trainerOverviewScope(ref.read(authControllerProvider));
    if (scope == null) return;
    ref.invalidate(traineeOverviewsProvider(scope));
    try {
      final request = ref.refresh(ownTraineesProvider(scope.accountId).future);
      await request;
    } catch (_) {
      // The list shows its error and retry action.
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final scope = trainerOverviewScope(auth);
    if (scope == null) return const SizedBox();
    final canCreateAccounts = switch (auth) {
      Authenticated(:final session) => session.can(
        Session.customerAccountCreate,
      ),
      _ => false,
    };
    final provider = ownTraineesProvider(scope.accountId);

    return ref
        .watch(provider)
        .when(
          skipLoadingOnRefresh: false,
          skipLoadingOnReload: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _LoadError(
            message: traineesErrorMessage(error, l10n, loading: true),
            onRetry: () => ref.invalidate(provider),
          ),
          data: (trainees) {
            final summaries = trainees.isEmpty
                ? null
                : ref.watch(traineeOverviewsProvider(scope));
            final summaryData = summaries?.asData?.value;
            final byCustomer = {
              for (final item in summaryData?.items ?? <TraineeOverview>[])
                item.customerId: item,
            };
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  FilledButton.icon(
                    onPressed: () async {
                      await context.push(newCooperationLocation);
                      if (mounted) await _refresh();
                    },
                    icon: const Icon(Icons.person_add_alt_1),
                    label: Text(l10n.traineesNewCooperation),
                  ),
                  if (canCreateAccounts) ...[
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: () async {
                        await context.push(newClientAccountLocation);
                        if (mounted) await _refresh();
                      },
                      icon: const Icon(Icons.mark_email_unread_outlined),
                      label: Text(l10n.traineesNewClientAccount),
                    ),
                  ],
                  const SizedBox(height: 16),
                  if (summaries?.hasError ?? false)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Row(
                        children: [
                          const Icon(Icons.cloud_off_outlined, size: 20),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(l10n.traineeOverviewSummaryError),
                          ),
                          IconButton(
                            onPressed: () =>
                                ref.invalidate(traineeOverviewsProvider(scope)),
                            tooltip: l10n.traineesRetry,
                            icon: const Icon(Icons.refresh),
                          ),
                        ],
                      ),
                    ),
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
                      overview: byCustomer[trainee.customerId],
                      checkedAt: summaryData?.checkedAt,
                      overviewLoading: summaries?.isLoading ?? false,
                      onOpen: trainee.isActive
                          ? () => context.push(
                              '/trainer/trainings/trainees/${trainee.customerId}',
                            )
                          : null,
                      onWrite: () => _write(context, ref, trainee),
                      onEnd: () => _endCooperation(context, ref, trainee),
                    ),
                ],
              ),
            );
          },
        );
  }

  /// Opens the conversation with the trainee, creating it if needed.
  Future<void> _write(
    BuildContext context,
    WidgetRef ref,
    Trainee trainee,
  ) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final conversation = await ref
          .read(conversationsApiProvider)
          .startWithTrainee(trainee.customerId);
      if (context.mounted) {
        await context.push(
          conversationLocation(AppRole.trainer, conversation.id),
        );
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
    }
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
      ref.invalidate(traineeOverviewsProvider);
    }
  }
}

class _TraineeCard extends StatelessWidget {
  const _TraineeCard(
    this.trainee, {
    required this.onWrite,
    required this.onEnd,
    required this.overview,
    required this.checkedAt,
    required this.overviewLoading,
    required this.onOpen,
  });

  final Trainee trainee;
  final VoidCallback onWrite;
  final VoidCallback onEnd;
  final VoidCallback? onOpen;
  final TraineeOverview? overview;
  final DateTime? checkedAt;
  final bool overviewLoading;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final details = [
      if (trainee.isChild) l10n.traineesChild,
      if (trainee.isChild && trainee.birthDate != null)
        l10n.traineesBirthDate(formatBirthDate(trainee.birthDate!)),
      if (!trainee.isActive) l10n.traineesInactive,
    ];

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onOpen,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 6,
            children: [
              Row(
                children: [
                  CircleAvatar(
                    radius: 16,
                    child: Text(
                      _initials(trainee.displayName),
                      style: Theme.of(context).textTheme.labelMedium,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 3,
                      children: [
                        Text(
                          trainee.displayName,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                        if (details.isNotEmpty)
                          Text(
                            details.join(' · '),
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        if (trainee.isAwaitingActivation)
                          _AwaitingActivation(l10n.traineesAwaitingActivation),
                      ],
                    ),
                  ),
                  PopupMenuButton<void>(
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(
                      minWidth: 32,
                      minHeight: 32,
                    ),
                    tooltip: l10n.traineesActions,
                    itemBuilder: (_) => [
                      if (trainee.isActive)
                        PopupMenuItem(
                          onTap: onWrite,
                          child: Text(l10n.traineesWrite),
                        ),
                      PopupMenuItem(
                        onTap: onEnd,
                        child: Text(l10n.traineesEndConfirm),
                      ),
                    ],
                  ),
                ],
              ),
              if (overviewLoading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 12),
                  child: LinearProgressIndicator(),
                )
              else if (overview case final overview?) ...[
                TraineePackageSummary(overview),
                if (overview.nextTraining case final next?)
                  TraineeTrainingLink(next, checkedAt: checkedAt!)
                else if (trainee.isActive)
                  Text(
                    l10n.traineeOverviewNoNext,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                if (overview.attentionTraining case final attention?)
                  TraineeTrainingLink(
                    attention,
                    checkedAt: checkedAt!,
                    attention: true,
                  ),
              ],
            ],
          ),
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

/// Marks a trainee whose account was created by invitation and who has not
/// set a password yet.
class _AwaitingActivation extends StatelessWidget {
  const _AwaitingActivation(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      spacing: 4,
      children: [
        Icon(
          Icons.schedule_send_outlined,
          size: 16,
          color: theme.colorScheme.tertiary,
        ),
        Flexible(
          child: Text(
            label,
            style: theme.textTheme.labelMedium?.copyWith(
              color: theme.colorScheme.tertiary,
            ),
          ),
        ),
      ],
    );
  }
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

/// A date of birth as shown next to a participant's name, e.g. `12.05.2015`.
String formatBirthDate(DateTime date) =>
    DateFormat('dd.MM.y', 'pl').format(date);

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
