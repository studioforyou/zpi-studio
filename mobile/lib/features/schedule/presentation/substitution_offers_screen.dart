import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/substitution_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class SubstitutionOffersScreen extends ConsumerWidget {
  const SubstitutionOffersScreen({super.key, this.initialRequestId});
  final String? initialRequestId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    return scope == null
        ? const SizedBox.shrink()
        : _OffersPage(
            key: ValueKey(scope),
            scope: scope,
            initialRequestId: initialRequestId,
          );
  }
}

class _OffersPage extends ConsumerStatefulWidget {
  const _OffersPage({super.key, required this.scope, this.initialRequestId});
  final TrainerOverviewScope scope;
  final String? initialRequestId;
  @override
  ConsumerState<_OffersPage> createState() => _OffersPageState();
}

class _OffersPageState extends ConsumerState<_OffersPage>
    with WidgetsBindingObserver {
  bool _mine = false;
  bool _matching = true;
  int _days = 30;
  String? _kind;
  Timer? _timer;

  OfferQuery get _query {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return (
      scope: widget.scope,
      mine: _mine,
      from: _mine ? today.subtract(const Duration(days: 30)) : today,
      to: today.add(Duration(days: _mine ? 60 : _days)),
    );
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refresh());
    _openInitial();
  }

  @override
  void didUpdateWidget(covariant _OffersPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.initialRequestId != widget.initialRequestId) _openInitial();
  }

  void _openInitial() {
    final id = widget.initialRequestId;
    if (id != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _open(id);
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    if (!mounted ||
        trainerOverviewScope(ref.read(authControllerProvider)) !=
            widget.scope) {
      return;
    }
    try {
      // A studio decision can arrive while the trainer is reading the offer.
      ref.invalidate(substitutionOfferProvider);
      ref.invalidate(ownPersonalTrainingsProvider);
      ref.invalidate(trainerScheduleEventsProvider);
      ref.invalidate(ownScheduleProvider);
      final request = ref.refresh(substitutionOffersProvider(_query).future);
      await request;
    } catch (_) {
      /* Provider renders retry. */
    }
  }

  Future<void> _open(String id) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _OfferSheet(scope: widget.scope, id: id),
    );
    if (mounted) await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final data = ref.watch(substitutionOffersProvider(_query));
    return Scaffold(
      appBar: AppBar(
        title: Text(l.substitutionOffersTitle),
        actions: [
          IconButton(
            tooltip: l.substitutionOffersInfoTitle,
            icon: const Icon(Icons.info_outline),
            onPressed: () => showDialog<void>(
              context: context,
              builder: (context) => AlertDialog(
                title: Text(l.substitutionOffersInfoTitle),
                content: Text(l.substitutionOffersInfo),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: Text(l.substitutionClose),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: SizedBox(
              width: double.infinity,
              child: SegmentedButton<bool>(
                direction:
                    MediaQuery.sizeOf(context).width < 400 &&
                        MediaQuery.textScalerOf(context).scale(14) > 18
                    ? Axis.vertical
                    : Axis.horizontal,
                showSelectedIcon: false,
                segments: [
                  ButtonSegment(
                    value: false,
                    label: Text(l.substitutionAvailable),
                  ),
                  ButtonSegment(
                    value: true,
                    label: Text(l.substitutionMyClaims),
                  ),
                ],
                selected: {_mine},
                onSelectionChanged: (v) => setState(() => _mine = v.first),
              ),
            ),
          ),
          if (!_mine)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Wrap(
                spacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  FilterChip(
                    label: Text(l.substitutionMatching),
                    selected: _matching,
                    onSelected: (v) => setState(() => _matching = v),
                  ),
                  DropdownButton<int>(
                    value: _days,
                    underline: const SizedBox(),
                    items: [7, 30, 60]
                        .map(
                          (n) => DropdownMenuItem(
                            value: n,
                            child: Text(l.substitutionDays(n)),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => setState(() => _days = v!),
                  ),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 224),
                    child: DropdownButton<String?>(
                      value: _kind,
                      isExpanded: true,
                      underline: const SizedBox(),
                      items: [
                        DropdownMenuItem(
                          value: null,
                          child: Text(l.substitutionAllKinds),
                        ),
                        DropdownMenuItem(
                          value: 'CLASS',
                          child: Text(l.substitutionGroup),
                        ),
                        DropdownMenuItem(
                          value: 'PERSONAL_TRAINING',
                          child: Text(
                            l.substitutionPersonal,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                      onChanged: (v) => setState(() => _kind = v),
                    ),
                  ),
                ],
              ),
            ),
          Expanded(
            child: data.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => _LoadError(onRetry: _refresh),
              data: (all) {
                final items = all
                    .where(
                      (r) =>
                          (_mine || !_matching || r.canClaim) &&
                          (_mine || _kind == null || r.kind == _kind),
                    )
                    .toList();
                return RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                    children: items.isEmpty
                        ? [
                            Padding(
                              padding: const EdgeInsets.all(32),
                              child: Column(
                                children: [
                                  const Icon(
                                    Icons.event_available_outlined,
                                    size: 40,
                                  ),
                                  const SizedBox(height: 12),
                                  Text(
                                    _mine
                                        ? l.substitutionNoClaims
                                        : l.substitutionNoOffers,
                                    textAlign: TextAlign.center,
                                  ),
                                ],
                              ),
                            ),
                          ]
                        : [
                            for (final offer in items)
                              _OfferCard(
                                offer: offer,
                                mine: _mine,
                                onTap: () => _open(offer.id),
                              ),
                          ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _OfferCard extends StatelessWidget {
  const _OfferCard({
    required this.offer,
    required this.mine,
    required this.onTap,
  });
  final SubstitutionOffer offer;
  final bool mine;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context), theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 62,
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer.withValues(
                    alpha: .5,
                  ),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  children: [
                    Text(
                      DateFormat('dd.MM').format(offer.startsAt),
                      style: theme.textTheme.titleSmall,
                    ),
                    Text(
                      DateFormat('EEE', 'pl').format(offer.startsAt),
                      style: theme.textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      offer.title,
                      style: theme.textTheme.titleSmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    Text(
                      offerTime(offer),
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    Text(
                      '${offer.originalTrainer} · ${offer.rooms}',
                      style: theme.textTheme.bodySmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 6),
                    if (mine)
                      _Status(offer.status)
                    else if (!offer.canClaim)
                      Text(
                        unavailableLabel(l, offer.unavailableReason),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      )
                    else
                      Text(
                        offer.kind == 'PERSONAL_TRAINING'
                            ? l.substitutionPersonal
                            : l.substitutionGroup,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.primary,
                        ),
                      ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _OfferSheet extends ConsumerStatefulWidget {
  const _OfferSheet({required this.scope, required this.id});
  final TrainerOverviewScope scope;
  final String id;
  @override
  ConsumerState<_OfferSheet> createState() => _OfferSheetState();
}

class _OfferSheetState extends ConsumerState<_OfferSheet> {
  bool _saving = false;
  String? _error;
  OfferDetailsQuery get _query => (scope: widget.scope, id: widget.id);
  bool get _current =>
      mounted &&
      trainerOverviewScope(ref.read(authControllerProvider)) == widget.scope;

  Future<void> _act(SubstitutionOffer offer, bool withdraw) async {
    final l = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          withdraw ? l.substitutionWithdrawClaim : l.substitutionClaimAction,
        ),
        content: Text(
          withdraw
              ? l.substitutionWithdrawHint
              : offer.approvalRequired
              ? l.substitutionApprovalHint
              : l.substitutionAutomaticHint,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.substitutionClose),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(
              withdraw ? l.substitutionWithdraw : l.substitutionConfirmClaim,
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !_current) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(substitutionApiProvider)
          .actOnOffer(offer, withdraw: withdraw);
      if (!_current) return;
      ref.invalidate(substitutionOfferProvider(_query));
      ref.invalidate(substitutionOffersProvider);
      ref.invalidate(ownSubstitutionsProvider);
      ref.invalidate(ownPersonalTrainingsProvider);
      ref.invalidate(trainerScheduleEventsProvider);
      ref.invalidate(ownScheduleProvider);
    } catch (e) {
      if (!_current) return;
      setState(
        () => _error = switch (e) {
          SubstitutionException(
            code: 'VERSION_CONFLICT' || 'SUBSTITUTION_ALREADY_DECIDED',
          ) =>
            l.substitutionOfferChanged,
          SubstitutionException(code: 'SUBSTITUTION_NOT_ELIGIBLE') =>
            l.substitutionOfferConflict,
          _ => l.substitutionSaveError,
        },
      );
      ref.invalidate(substitutionOfferProvider(_query));
    } finally {
      if (_current) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context), theme = Theme.of(context);
    if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
        widget.scope) {
      return const SizedBox.shrink();
    }
    return PopScope(
      canPop: !_saving,
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: ref
              .watch(substitutionOfferProvider(_query))
              .when(
                loading: () => const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (_, _) => _LoadError(
                  onRetry: () =>
                      ref.refresh(substitutionOfferProvider(_query).future),
                ),
                data: (offer) => Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(offer.title, style: theme.textTheme.titleLarge),
                    const SizedBox(height: 12),
                    _Status(offer.status),
                    const SizedBox(height: 16),
                    Text(
                      '${DateFormat('EEEE, d MMMM', 'pl').format(offer.startsAt)} · ${offerTime(offer)}',
                      style: theme.textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(offer.rooms),
                    Text(
                      l.substitutionForTrainer(offer.originalTrainer),
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    if (offer.substituteTrainer != null &&
                        offer.status != 'OPEN')
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: Text(
                          l.substitutionCandidateName(offer.substituteTrainer!),
                        ),
                      ),
                    if (offer.decisionNote != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(offer.decisionNote!),
                      ),
                    if (offer.warnings.isNotEmpty &&
                        (offer.canClaim || offer.canWithdraw))
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(
                          l.substitutionAvailabilityWarning,
                          style: TextStyle(color: theme.colorScheme.tertiary),
                        ),
                      ),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(
                          _error!,
                          style: TextStyle(color: theme.colorScheme.error),
                        ),
                      ),
                    const SizedBox(height: 20),
                    if (offer.canClaim) ...[
                      Text(
                        offer.approvalRequired
                            ? l.substitutionApprovalHint
                            : l.substitutionAutomaticHint,
                        style: theme.textTheme.bodySmall,
                      ),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: _saving ? null : () => _act(offer, false),
                        icon: _saving
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.swap_horiz),
                        label: Text(l.substitutionClaimAction),
                      ),
                    ] else if (offer.canWithdraw) ...[
                      Text(
                        l.substitutionPendingHint,
                        style: theme.textTheme.bodySmall,
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed: _saving ? null : () => _act(offer, true),
                        child: Text(l.substitutionWithdrawClaim),
                      ),
                    ] else
                      Text(
                        offer.status == 'APPROVED'
                            ? l.substitutionAcceptedHint
                            : unavailableLabel(l, offer.unavailableReason),
                        style: theme.textTheme.bodyMedium,
                      ),
                  ],
                ),
              ),
        ),
      ),
    );
  }
}

class _Status extends StatelessWidget {
  const _Status(this.status);
  final String status;
  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final (label, color, icon) = switch (status) {
      'OPEN' => (l.substitutionOfferOpen, Colors.blue, Icons.swap_horiz),
      'CLAIMED' => (l.substitutionOfferPending, Colors.orange, Icons.schedule),
      'APPROVED' => (
        l.substitutionOfferApproved,
        Colors.green,
        Icons.check_circle_outline,
      ),
      'REJECTED' => (
        l.substitutionOfferRejected,
        Colors.red,
        Icons.cancel_outlined,
      ),
      'CANCELLED' => (l.substitutionOfferWithdrawn, Colors.grey, Icons.close),
      _ => (l.substitutionOfferExpired, Colors.grey, Icons.event_busy_outlined),
    };
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: color.withValues(alpha: .1),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 16, color: color),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                label,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w600,
                  fontSize: 12,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.onRetry});
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            AppLocalizations.of(context).substitutionOffersLoadError,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: Text(AppLocalizations.of(context).notificationsRetry),
          ),
        ],
      ),
    ),
  );
}

String offerTime(SubstitutionOffer offer) =>
    '${DateFormat('HH:mm').format(offer.startsAt)}–${DateFormat('HH:mm').format(offer.endsAt)}';

String unavailableLabel(AppLocalizations l, String? reason) => switch (reason) {
  'NOT_QUALIFIED' => l.substitutionNotQualified,
  'UNAVAILABLE' => l.substitutionBusy,
  'OWN_EVENT' => l.substitutionOwnEvent,
  'INACTIVE' => l.substitutionInactive,
  _ => l.substitutionOfferUnavailable,
};
