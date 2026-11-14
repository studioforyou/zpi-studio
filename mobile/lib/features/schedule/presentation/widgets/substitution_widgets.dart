import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/substitution_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class SubstitutionStatusChip extends StatelessWidget {
  const SubstitutionStatusChip(this.request, {super.key});
  final SubstitutionRequest request;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final claimed = request.status == 'CLAIMED';
    final color = claimed ? Colors.blue.shade700 : Colors.orange.shade900;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .09),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.swap_horiz, size: 17, color: color),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              claimed ? l10n.substitutionClaimed : l10n.substitutionOpen,
              style: Theme.of(
                context,
              ).textTheme.labelMedium?.copyWith(color: color),
            ),
          ),
        ],
      ),
    );
  }
}

/// Reused by the group-event screen and the personal-session sheet.
class OwnSubstitutionSection extends ConsumerWidget {
  const OwnSubstitutionSection({super.key, required this.event});
  final ScheduleEvent event;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    if (scope == null ||
        event.status != EventStatus.scheduled ||
        !event.startsAt.isAfter(DateTime.now()) ||
        !event.staff.any((s) => s.staffId == scope.staffId && s.isLead)) {
      return const SizedBox();
    }
    final query = (scope: scope, eventId: substitutionEventId(event));
    return _Controls(key: ValueKey(query), event: event, query: query);
  }
}

class _Controls extends ConsumerStatefulWidget {
  const _Controls({super.key, required this.event, required this.query});
  final ScheduleEvent event;
  final SubstitutionQuery query;
  @override
  ConsumerState<_Controls> createState() => _ControlsState();
}

class _ControlsState extends ConsumerState<_Controls> {
  bool _saving = false;
  Object? _error;
  bool get _active =>
      mounted &&
      trainerOverviewScope(ref.read(authControllerProvider)) ==
          widget.query.scope;

  Future<void> _cancel(SubstitutionRequest request) async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.substitutionCancelTitle),
        content: Text(l10n.substitutionCancelHint),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.substitutionKeep),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.substitutionWithdraw),
          ),
        ],
      ),
    );
    if (!_active || confirmed != true || _saving) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(substitutionApiProvider).cancel(request.id);
      if (!_active) return;
      _invalidate(ref, widget.query);
    } catch (e) {
      if (!_active) return;
      setState(() => _error = e);
      _invalidate(ref, widget.query);
    } finally {
      if (_active) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final state = ref.watch(ownSubstitutionStateProvider(widget.query));
    return state.when(
      loading: () => OutlinedButton.icon(
        onPressed: null,
        icon: const Icon(Icons.swap_horiz),
        label: Text(l10n.substitutionAction),
      ),
      error: (_, _) => Row(
        children: [
          Expanded(child: Text(l10n.substitutionLoadError)),
          IconButton(
            tooltip: l10n.scheduleRetryButton,
            onPressed: () =>
                ref.invalidate(ownSubstitutionStateProvider(widget.query)),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      data: (state) {
        final request = state.request;
        if (request == null) {
          if (!state.canRequest) return const SizedBox();
          return OutlinedButton.icon(
            key: const Key('requestSubstitution'),
            onPressed: () => showModalBottomSheet<void>(
              context: context,
              showDragHandle: true,
              isScrollControlled: true,
              builder: (_) =>
                  _RequestForm(event: widget.event, query: widget.query),
            ),
            icon: const Icon(Icons.swap_horiz),
            label: Text(l10n.substitutionAction),
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 8,
              children: [
                SubstitutionStatusChip(request),
                if (request.reason case final reason? when reason.isNotEmpty)
                  IconButton(
                    tooltip: l10n.substitutionReasonTitle,
                    icon: const Icon(Icons.info_outline),
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: Text(l10n.substitutionReasonTitle),
                        content: SingleChildScrollView(child: Text(reason)),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context),
                            child: Text(l10n.substitutionClose),
                          ),
                        ],
                      ),
                    ),
                  ),
                if (request.canCancel)
                  TextButton(
                    key: const Key('withdrawSubstitution'),
                    onPressed: _saving ? null : () => _cancel(request),
                    child: Text(
                      _saving
                          ? l10n.substitutionSaving
                          : l10n.substitutionWithdraw,
                    ),
                  ),
              ],
            ),
            if (_error != null)
              Text(
                _message(_error!, l10n),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
          ],
        );
      },
    );
  }
}

class _RequestForm extends ConsumerStatefulWidget {
  const _RequestForm({required this.event, required this.query});
  final ScheduleEvent event;
  final SubstitutionQuery query;
  @override
  ConsumerState<_RequestForm> createState() => _RequestFormState();
}

class _RequestFormState extends ConsumerState<_RequestForm> {
  final _reason = TextEditingController();
  bool _saving = false;
  Object? _error;
  bool get _active =>
      mounted &&
      trainerOverviewScope(ref.read(authControllerProvider)) ==
          widget.query.scope;
  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (_saving || !_active) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(substitutionApiProvider)
          .create(widget.query.eventId, _reason.text);
      if (!mounted || !_active) return;
      _invalidate(ref, widget.query);
      Navigator.pop(context);
    } catch (e) {
      if (!_active) return;
      setState(() {
        _saving = false;
        _error = e;
      });
      _invalidate(ref, widget.query);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    return PopScope(
      canPop: !_saving || scope != widget.query.scope,
      child: SafeArea(
        child: SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            20,
            0,
            20,
            20 + MediaQuery.viewInsetsOf(context).bottom,
          ),
          child: scope != widget.query.scope
              ? const SizedBox()
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 12,
                  children: [
                    Text(
                      l10n.substitutionAction,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    Text(
                      widget.event is TraineeTraining
                          ? (widget.event as TraineeTraining).customerName ??
                                widget.event.name ??
                                ''
                          : widget.event.name ?? '',
                    ),
                    Text(
                      '${DateFormat('EEE, d MMM · HH:mm', 'pl').format(widget.event.startsAt)}–${DateFormat('HH:mm').format(widget.event.endsAt)}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    TextField(
                      key: const Key('substitutionReason'),
                      controller: _reason,
                      enabled: !_saving,
                      minLines: 2,
                      maxLines: 4,
                      maxLength: 1000,
                      decoration: InputDecoration(
                        labelText: l10n.substitutionReason,
                        border: const OutlineInputBorder(),
                      ),
                    ),
                    if (_error != null)
                      Text(
                        _message(_error!, l10n),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    FilledButton.icon(
                      key: const Key('sendSubstitution'),
                      onPressed: _saving ? null : _send,
                      icon: const Icon(Icons.swap_horiz),
                      label: Text(
                        _saving
                            ? l10n.substitutionSaving
                            : l10n.substitutionSend,
                      ),
                    ),
                  ],
                ),
        ),
      ),
    );
  }
}

void _invalidate(WidgetRef ref, SubstitutionQuery query) {
  ref.invalidate(ownSubstitutionStateProvider(query));
  ref.invalidate(ownSubstitutionsProvider);
}

String _message(Object error, AppLocalizations l10n) {
  if (error is SubstitutionException) {
    switch (error.code) {
      case 'SUBSTITUTION_ALREADY_REQUESTED':
        return l10n.substitutionAlreadyOpen;
      case 'SUBSTITUTION_NOT_OPEN':
        return l10n.substitutionNotOpen;
      case 'EVENT_NOT_EDITABLE':
        return l10n.substitutionNotEditable;
      case 'STAFF_UNAVAILABLE':
        return l10n.substitutionUnavailable;
      case 'VALIDATION_FAILED':
        return l10n.substitutionInvalidReason;
    }
  }
  if (error is ApiException) {
    if (error.statusCode == null) return l10n.substitutionNetworkError;
    if (error.statusCode == 404 || error.statusCode == 403) {
      return l10n.substitutionUnavailable;
    }
  }
  return l10n.substitutionSaveError;
}
