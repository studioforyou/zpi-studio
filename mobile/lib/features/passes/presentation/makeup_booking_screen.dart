import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class MakeupBookingScreen extends ConsumerStatefulWidget {
  const MakeupBookingScreen({
    super.key,
    required this.scope,
    required this.creditId,
  });
  final MakeupScope scope;
  final String creditId;
  @override
  ConsumerState<MakeupBookingScreen> createState() =>
      _MakeupBookingScreenState();
}

class _MakeupBookingScreenState extends ConsumerState<MakeupBookingScreen>
    with WidgetsBindingObserver {
  late DateTime _month;
  bool _busy = false;
  String? _message;
  MakeupBookingQuery get _query =>
      (scope: widget.scope, creditId: widget.creditId, month: _month);
  bool get _current =>
      makeupBookingScope(ref.read(authControllerProvider)) == widget.scope;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _month = DateTime(now.year, now.month);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _current) {
      ref.invalidate(makeupBookingProvider(_query));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final valid =
        makeupBookingScope(ref.watch(authControllerProvider)) == widget.scope;
    final now = DateTime.now();
    return Scaffold(
      appBar: AppBar(title: Text(l10n.makeupBookingTitle)),
      body: !valid
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(l10n.classesProfileChanged),
              ),
            )
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Row(
                    children: [
                      IconButton(
                        tooltip: l10n.makeupPrevious,
                        icon: const Icon(Icons.chevron_left),
                        onPressed:
                            _busy ||
                                !_month.isAfter(DateTime(now.year, now.month))
                            ? null
                            : () => _move(-1),
                      ),
                      Expanded(
                        child: Text(
                          DateFormat('LLLL yyyy', 'pl').format(_month),
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      IconButton(
                        tooltip: l10n.makeupNext,
                        icon: const Icon(Icons.chevron_right),
                        onPressed: _busy ? null : () => _move(1),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: ref
                      .watch(makeupBookingProvider(_query))
                      .when(
                        skipLoadingOnRefresh: false,
                        skipLoadingOnReload: false,
                        loading: () =>
                            const Center(child: CircularProgressIndicator()),
                        error: (error, _) => Center(
                          child: SingleChildScrollView(
                            padding: const EdgeInsets.all(24),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              spacing: 12,
                              children: [
                                Text(
                                  _error(l10n, error),
                                  textAlign: TextAlign.center,
                                ),
                                OutlinedButton(
                                  onPressed: () => ref.invalidate(
                                    makeupBookingProvider(_query),
                                  ),
                                  child: Text(l10n.passesRetryButton),
                                ),
                              ],
                            ),
                          ),
                        ),
                        data: (offer) => RefreshIndicator(
                          onRefresh: () =>
                              ref.refresh(makeupBookingProvider(_query).future),
                          child: ListView(
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.all(16),
                            children: [
                              Container(
                                padding: const EdgeInsets.all(14),
                                decoration: BoxDecoration(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .primaryContainer
                                      .withValues(alpha: .45),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  spacing: 4,
                                  children: [
                                    if (_participant() case final name?)
                                      Text(
                                        name,
                                        style: Theme.of(
                                          context,
                                        ).textTheme.titleSmall,
                                      ),
                                    Text(offer.productName),
                                    Text(
                                      l10n.makeupExpiresAt(
                                        DateFormat(
                                          'dd.MM.yyyy, HH:mm',
                                        ).format(offer.expiresAt),
                                      ),
                                      style: Theme.of(
                                        context,
                                      ).textTheme.bodySmall,
                                    ),
                                  ],
                                ),
                              ),
                              if (_message != null)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                  ),
                                  child: Text(
                                    _message!,
                                    style: TextStyle(
                                      color: Theme.of(
                                        context,
                                      ).colorScheme.error,
                                    ),
                                  ),
                                ),
                              const SizedBox(height: 12),
                              if (offer.items.isEmpty)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 24,
                                  ),
                                  child: Text(l10n.makeupNoClasses),
                                ),
                              for (final event in offer.items)
                                Card.outlined(
                                  child: Padding(
                                    padding: const EdgeInsets.all(14),
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      spacing: 8,
                                      children: [
                                        Text(
                                          '${DateFormat('EEE, d MMM · HH:mm', 'pl').format(event.startsAt)}–${DateFormat('HH:mm').format(event.endsAt)}',
                                          style: Theme.of(
                                            context,
                                          ).textTheme.titleSmall,
                                        ),
                                        Text(
                                          event.name ?? l10n.classesTitle,
                                          style: Theme.of(
                                            context,
                                          ).textTheme.titleMedium,
                                        ),
                                        if (event.staff.isNotEmpty ||
                                            event.roomNames.isNotEmpty)
                                          Text(
                                            [
                                              ...event.staff.map(
                                                (person) => person.displayName,
                                              ),
                                              ...event.roomNames,
                                            ].join(' · '),
                                            style: Theme.of(
                                              context,
                                            ).textTheme.bodySmall,
                                          ),
                                        Wrap(
                                          spacing: 12,
                                          runSpacing: 8,
                                          crossAxisAlignment:
                                              WrapCrossAlignment.center,
                                          children: [
                                            Text(
                                              event.booking.allowed
                                                  ? classPlaces(l10n, event)
                                                  : classReason(
                                                      l10n,
                                                      event.booking.reason,
                                                    ),
                                            ),
                                            FilledButton.icon(
                                              onPressed:
                                                  _busy ||
                                                      !event.booking.allowed
                                                  ? null
                                                  : () =>
                                                        _reserve(offer, event),
                                              icon: const Icon(
                                                Icons.event_repeat_outlined,
                                                size: 18,
                                              ),
                                              label: Text(l10n.makeupBook),
                                            ),
                                          ],
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                ),
              ],
            ),
    );
  }

  void _move(int change) => setState(() {
    _month = DateTime(_month.year, _month.month + change);
    _message = null;
  });

  String? _participant() => clientSession(ref.read(authControllerProvider))
      ?.profiles
      .where((p) => p.id == widget.scope.profileId)
      .firstOrNull
      ?.displayName;

  Future<void> _reserve(MakeupOffer offer, GroupClass event) async {
    if (_busy || !_current) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => Consumer(
          builder: (context, ref, _) {
            final valid =
                mounted &&
                makeupBookingScope(ref.watch(authControllerProvider)) ==
                    widget.scope;
            return AlertDialog(
              title: Text(
                valid ? l10n.makeupConfirm : l10n.classesProfileChanged,
              ),
              scrollable: true,
              content: !valid
                  ? null
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 8,
                      children: [
                        Text(event.name ?? l10n.classesTitle),
                        Text(classDateTime(event.startsAt)),
                        if (_participant() case final name?)
                          Text(l10n.classesReserveFor(name)),
                        Text(
                          l10n.makeupCost,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ],
                    ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(l10n.classesBack),
                ),
                if (valid)
                  FilledButton(
                    onPressed: () => Navigator.of(context).pop(true),
                    child: Text(l10n.makeupBook),
                  ),
              ],
            );
          },
        ),
      );
      if (confirmed != true || !mounted || !_current) return;
      await ref.read(makeupBookingApiProvider).reserve(_query, event.id);
      if (!mounted) return;
      ref.invalidate(makeupCreditsProvider);
      ref.invalidate(groupClassesProvider);
      ref.invalidate(classReservationsProvider);
      final next = (scope: widget.scope, eventId: event.id);
      ref.invalidate(groupClassProvider(next));
      if (!mounted || !_current) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l10n.classesBookingSuccess)));
      Navigator.of(context).pushReplacement<void, void>(
        MaterialPageRoute(builder: (_) => GroupClassDetailsScreen(query: next)),
      );
    } catch (error) {
      if (!mounted) return;
      ref.invalidate(makeupCreditsProvider);
      ref.invalidate(makeupBookingProvider(_query));
      if (mounted && _current) setState(() => _message = _error(l10n, error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _error(AppLocalizations l10n, Object error) => switch (error) {
    ClassBookingException(code: 'MAKEUP_CREDIT_NOT_AVAILABLE') =>
      l10n.makeupUnavailable,
    ClassBookingException(code: 'MAKEUP_CREDIT_NOT_USABLE') =>
      l10n.makeupNotUsable,
    ClassBookingException(:final code) when code != null => classReason(
      l10n,
      code,
    ),
    _ => l10n.makeupOfferError,
  };
}
