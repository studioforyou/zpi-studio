import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/class_participants_section.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_summary.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class GroupClassDetailsScreen extends ConsumerStatefulWidget {
  const GroupClassDetailsScreen({required this.query, super.key});

  final ClassQuery query;

  @override
  ConsumerState<GroupClassDetailsScreen> createState() =>
      _GroupClassDetailsScreenState();
}

class _GroupClassDetailsScreenState
    extends ConsumerState<GroupClassDetailsScreen> {
  bool _busy = false;
  bool _sending = false;
  String? _actionMessage;
  final _scrollController = ScrollController();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final activeScope = bookingScope(ref.watch(authControllerProvider));
    final provider = groupClassProvider(widget.query);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.classesDetails)),
      body: activeScope != widget.query.scope
          ? Column(
              children: [
                ClassMessage(l10n.classesProfileChanged),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(l10n.classesBack),
                ),
              ],
            )
          : RefreshIndicator(
              onRefresh: () => ref.refresh(provider.future),
              child: ListView(
                controller: _scrollController,
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                children: [
                  if (_actionMessage case final message?)
                    Card(
                      color: Theme.of(context).colorScheme.errorContainer,
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(message),
                      ),
                    ),
                  ...ref
                      .watch(provider)
                      .when(
                        skipLoadingOnRefresh: false,
                        skipLoadingOnReload: false,
                        loading: () => const [
                          Padding(
                            padding: EdgeInsets.all(48),
                            child: Center(child: CircularProgressIndicator()),
                          ),
                        ],
                        error: (_, _) => [
                          ClassesLoadError(
                            onRetry: () => ref.invalidate(provider),
                          ),
                        ],
                        data: _details,
                      ),
                ],
              ),
            ),
    );
  }

  List<Widget> _details(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final participant = _participantName();
    final reservation = event.ownReservation;
    final isReserved = event.isReserved;
    final canReserve =
        event.booking.allowed && event.booking.eligiblePasses.isNotEmpty;
    return [
      GroupClassSummary(event: event, participant: participant),
      const SizedBox(height: 16),
      if (isReserved && reservation != null) ...[
        ClassInfoLine(Icons.check_circle_outline, l10n.classesReserved),
        if (reservation.freeCancelUntil case final deadline?
            when reservation.cancellation.allowed &&
                reservation.cancellation.entryReturned == true) ...[
          const SizedBox(height: 12),
          Text(l10n.classesFreeCancelUntil(classDateTime(deadline))),
        ],
        if (!reservation.cancellation.allowed ||
            reservation.cancellation.entryReturned != true) ...[
          const SizedBox(height: 12),
          Text(
            reservation.cancellation.allowed
                ? l10n.classesCancelForfeitsEntry
                : classReason(l10n, reservation.cancellation.reason),
          ),
        ],
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: _busy || !reservation.cancellation.allowed
              ? null
              : () => _cancel(event, reservation),
          icon: const Icon(Icons.event_busy_outlined),
          label: Text(l10n.classesCancel),
        ),
      ] else ...[
        if (!canReserve) ...[
          Text(classReason(l10n, event.booking.reason ?? 'NO_ELIGIBLE_PASS')),
          const SizedBox(height: 16),
        ],
        FilledButton.icon(
          onPressed: _busy || !canReserve ? null : () => _reserve(event),
          icon: const Icon(Icons.add_task),
          label: Text(l10n.classesReserve),
        ),
      ],
      if (_sending)
        const Padding(
          padding: EdgeInsets.all(12),
          child: Center(child: CircularProgressIndicator()),
        ),
      ClassParticipantsSection(query: widget.query, hasReservation: isReserved),
    ];
  }

  Future<void> _reserve(GroupClass event) async {
    if (_busy || !isCurrentBookingScope(ref, widget.query.scope)) return;
    final l10n = AppLocalizations.of(context);
    final participant = _participantName();
    final passes = event.booking.eligiblePasses;
    if (passes.isEmpty) return;
    setState(() {
      _busy = true;
      _actionMessage = null;
    });
    String? selectedPass = passes.length == 1 ? passes.single.id : null;
    try {
      final passId = await showDialog<String>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, setDialogState) => AlertDialog(
            title: Text(l10n.classesReserveTitle),
            scrollable: true,
            content: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              spacing: 12,
              children: [
                Text(event.name ?? l10n.classesTitle),
                Text(classDateTime(event.startsAt)),
                if (participant != null)
                  Text(l10n.classesReserveFor(participant)),
                if (passes.length == 1)
                  Text(_passLabel(l10n, passes.single))
                else
                  DropdownButtonFormField<String>(
                    isExpanded: true,
                    decoration: InputDecoration(
                      labelText: l10n.classesChoosePass,
                      border: const OutlineInputBorder(),
                    ),
                    items: [
                      for (final pass in passes)
                        DropdownMenuItem(
                          value: pass.id,
                          child: Text(
                            _passLabel(l10n, pass),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (id) => setDialogState(() => selectedPass = id),
                  ),
                if (passes
                        .where((pass) => pass.id == selectedPass)
                        .firstOrNull
                        ?.entriesRemaining !=
                    null)
                  Text(l10n.classesReserveEntry),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(l10n.classesDismiss),
              ),
              FilledButton(
                onPressed: selectedPass == null
                    ? null
                    : () => Navigator.of(context).pop(selectedPass),
                child: Text(l10n.classesReserve),
              ),
            ],
          ),
        ),
      );
      if (passId == null ||
          !mounted ||
          !isCurrentBookingScope(ref, widget.query.scope)) {
        return;
      }
      setState(() => _sending = true);
      await ref.read(groupClassesApiProvider).reserve(widget.query, passId);
      _refreshAfterMutation();
      if (mounted && isCurrentBookingScope(ref, widget.query.scope)) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.classesBookingSuccess)));
      }
    } catch (error) {
      _onActionError(error);
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _sending = false;
        });
      }
    }
  }

  Future<void> _cancel(GroupClass event, ClassReservation reservation) async {
    if (_busy || !isCurrentBookingScope(ref, widget.query.scope)) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _actionMessage = null;
    });
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(l10n.classesCancelTitle),
          scrollable: true,
          content: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            spacing: 12,
            children: [
              Text(event.name ?? l10n.classesTitle),
              Text(classDateTime(event.startsAt)),
              if (_participantName() case final participant?)
                Text(l10n.classesReserveFor(participant)),
              Text(
                reservation.cancellation.entryReturned == true
                    ? l10n.classesCancelReturnsEntry
                    : l10n.classesCancelForfeitsEntry,
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(l10n.classesKeepReservation),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(l10n.classesCancel),
            ),
          ],
        ),
      );
      if (confirmed != true ||
          !mounted ||
          !isCurrentBookingScope(ref, widget.query.scope)) {
        return;
      }
      setState(() => _sending = true);
      await ref
          .read(groupClassesApiProvider)
          .cancel(widget.query.scope, reservation);
      _refreshAfterMutation();
      if (mounted && isCurrentBookingScope(ref, widget.query.scope)) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.classesCancelSuccess)));
      }
    } catch (error) {
      _onActionError(error);
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _sending = false;
        });
      }
    }
  }

  void _refreshAfterMutation() {
    if (!mounted) return;
    ref.invalidate(groupClassesProvider);
    ref.invalidate(classReservationsProvider);
    ref.invalidate(groupClassProvider(widget.query));
    ref.invalidate(classParticipantsProvider(widget.query));
    ref.invalidate(ownPassesProvider(widget.query.scope.profileId));
  }

  void _onActionError(Object error) {
    if (!mounted) return;
    _refreshAfterMutation();
    if (!isCurrentBookingScope(ref, widget.query.scope)) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _actionMessage = switch (error) {
        ClassBookingException(:final code, :final statusCode)
            when code == 'CANCELLATION_CONSEQUENCE_CHANGED' ||
                code == 'VERSION_CONFLICT' ||
                (statusCode == 409 && code == null) =>
          l10n.classesConditionsChanged,
        ClassBookingException(:final code) => classReason(l10n, code),
        _ => l10n.classesActionFailed,
      };
    });
    if (_scrollController.hasClients) _scrollController.jumpTo(0);
  }

  String _passLabel(AppLocalizations l10n, EligibleClassPass pass) {
    return [
      pass.productName,
      pass.entriesRemaining == null
          ? l10n.passEntriesUnlimited
          : l10n.classesPassEntries(pass.entriesRemaining!),
      if (pass.validUntil != null)
        l10n.passValidUntil(DateFormat('dd.MM.yyyy').format(pass.validUntil!)),
    ].join(' · ');
  }

  String? _participantName() => clientSession(ref.read(authControllerProvider))
      ?.profiles
      .where((profile) => profile.id == widget.query.scope.profileId)
      .firstOrNull
      ?.displayName;
}
