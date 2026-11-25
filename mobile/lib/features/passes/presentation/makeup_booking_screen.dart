import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/schedule/presentation/client/class_day_list.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The make-up mode (#281): the classes a make-up credit can be used for,
/// under a dark bar that says what is being made up and until when.
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
      isMakeupScopeCurrent(ref.read(authControllerProvider), widget.scope);

  static final _time = DateFormat('HH:mm');

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
    final ct = context.ct;
    final valid = isMakeupScopeCurrent(
      ref.watch(authControllerProvider),
      widget.scope,
    );
    final offer = valid ? ref.watch(makeupBookingProvider(_query)) : null;
    return Scaffold(
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Bar(
            title: [l10n.makeupModeTitle, ?_participant()].join(' · '),
            subtitle: switch (offer?.value) {
              final MakeupOffer offer => l10n.makeupModeHint(
                offer.productName,
                DateFormat('d.MM', 'pl').format(offer.expiresAt),
              ),
              null => null,
            },
          ),
          if (!valid)
            Expanded(
              child: Center(
                child: Padding(
                  padding: EdgeInsets.all(ct.spacing.xl),
                  child: Text(
                    l10n.classesProfileChanged,
                    textAlign: TextAlign.center,
                    style: ct.text.body,
                  ),
                ),
              ),
            )
          else ...[
            if (_message case final message?) ChangeStrip(message),
            _monthRow(context),
            Expanded(
              child: offer!.when(
                skipLoadingOnRefresh: false,
                skipLoadingOnReload: false,
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (error, _) => Center(
                  child: SingleChildScrollView(
                    padding: EdgeInsets.all(ct.spacing.xl),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      spacing: ct.spacing.m,
                      children: [
                        Text(
                          _error(l10n, error),
                          textAlign: TextAlign.center,
                          style: ct.text.body,
                        ),
                        AppButton(
                          label: l10n.passesRetryButton,
                          onPressed: () =>
                              ref.invalidate(makeupBookingProvider(_query)),
                        ),
                      ],
                    ),
                  ),
                ),
                data: (offer) => RefreshIndicator(
                  onRefresh: () =>
                      ref.refresh(makeupBookingProvider(_query).future),
                  child: _list(context, offer),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _monthRow(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final now = DateTime.now();
    final title = DateFormat('LLLL yyyy', 'pl').format(_month);
    final canGoBack = !_busy && _month.isAfter(DateTime(now.year, now.month));
    Widget arrow(IconData icon, String tooltip, VoidCallback? onPressed) =>
        IconButton(
          tooltip: tooltip,
          icon: Icon(
            icon,
            size: ct.sizes.icon,
            color: onPressed == null ? ct.colors.faint : ct.colors.muted,
          ),
          onPressed: onPressed,
        );
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: ct.spacing.s,
        vertical: ct.spacing.xs,
      ),
      child: Row(
        children: [
          arrow(
            Icons.chevron_left,
            l10n.makeupPrevious,
            canGoBack ? () => _move(-1) : null,
          ),
          Expanded(
            child: Text(
              title.characters.first.toUpperCase() +
                  title.characters.skip(1).toString(),
              textAlign: TextAlign.center,
              style: ct.text.monthTitle,
            ),
          ),
          arrow(
            Icons.chevron_right,
            l10n.makeupNext,
            _busy ? null : () => _move(1),
          ),
        ],
      ),
    );
  }

  Widget _list(BuildContext context, MakeupOffer offer) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    if (offer.items.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.all(ct.spacing.page),
        children: [
          Text(
            l10n.makeupNoClasses,
            style: ct.text.body.copyWith(color: ct.colors.muted),
          ),
        ],
      );
    }
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final byDay = <DateTime, List<GroupClass>>{};
    for (final event in offer.items) {
      final start = event.startsAt.toLocal();
      byDay
          .putIfAbsent(DateTime(start.year, start.month, start.day), () => [])
          .add(event);
    }
    final days = byDay.keys.toList()..sort();
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsets.only(bottom: ct.spacing.xl),
      children: [
        for (final day in days) ...[
          DayHeader(day: day, today: today),
          for (final (i, event) in byDay[day]!.indexed)
            ClassEntryRow(
              key: ValueKey(event.id),
              entry: _entry(context, offer, event),
              showStart:
                  i == 0 ||
                  !byDay[day]![i - 1].startsAt.isAtSameMomentAs(event.startsAt),
            ),
        ],
      ],
    );
  }

  ClassEntry _entry(BuildContext context, MakeupOffer offer, GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final c = context.ct.colors;
    final allowed = event.booking.allowed;
    final people = [
      for (final person in event.staff) ?person.displayName,
    ].join(', ');
    return ClassEntry(
      key: event.id,
      startsAt: event.startsAt.toLocal(),
      endsAt: event.endsAt.toLocal(),
      title: event.name ?? l10n.classesTitle,
      color: classColor(event.calendarColor, c),
      lines: [
        if (people.isNotEmpty) people,
        if (event.roomNames.isNotEmpty) event.roomNames.join(', '),
        if (!allowed) classReason(l10n, event.booking.reason),
      ],
      aside: allowed && event.availablePlaces != null
          ? PlacesAside(event.availablePlaces!)
          : null,
      dimmed: !allowed,
      onTap: allowed && !_busy ? () => _reserve(offer, event) : null,
    );
  }

  void _move(int change) => setState(() {
    _month = DateTime(_month.year, _month.month + change);
    _message = null;
  });

  String? _participant() {
    final session = clientSession(ref.read(authControllerProvider));
    if (session == null || session.profiles.length < 2) return null;
    return session.profiles
        .where((p) => p.id == widget.scope.profileId)
        .firstOrNull
        ?.displayName;
  }

  Future<bool?> _confirm(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final start = event.startsAt.toLocal();
    return showAppSheet<bool>(
      context,
      builder: (context) => Consumer(
        builder: (context, ref, _) {
          final valid =
              mounted &&
              isMakeupScopeCurrent(
                ref.watch(authControllerProvider),
                widget.scope,
              );
          return AppSheet(
            title: valid ? l10n.makeupConfirm : l10n.classesProfileChanged,
            titleSmall: true,
            subtitle: valid
                ? [
                    event.name ?? l10n.classesTitle,
                    '${classDateTime(start)}–${_time.format(event.endsAt.toLocal())}',
                    ?_participant(),
                  ].join(' · ')
                : null,
            footer: AppButtonPair(
              first: AppButton(
                label: l10n.classesBack,
                small: true,
                onPressed: () => Navigator.of(context).pop(),
              ),
              second: valid
                  ? AppButton(
                      label: l10n.makeupBook,
                      small: true,
                      onPressed: () => Navigator.of(context).pop(true),
                    )
                  : null,
            ),
            children: [
              if (valid)
                ConsequenceList(
                  items: [
                    Consequence(
                      icon: Icons.event_repeat_outlined,
                      title: l10n.makeupCost,
                      subtitle: l10n.makeupCancelReturns,
                    ),
                  ],
                ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _reserve(MakeupOffer offer, GroupClass event) async {
    if (_busy || !_current) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final confirmed = await _confirm(event);
      if (confirmed != true || !mounted || !_current) return;
      await ref.read(makeupBookingApiProvider).reserve(_query, event.id);
      if (!mounted) return;
      ref.invalidate(makeupCreditsProvider);
      ref.invalidate(groupClassesProvider);
      ref.invalidate(classReservationsProvider);
      ref.invalidate(
        groupClassProvider((scope: widget.scope, eventId: event.id)),
      );
      if (!mounted || !_current) return;
      // The booked class opens in Zajęcia, over its day. The class sheet
      // speaks for the active profile, so a make-up booked for another
      // person opens Zajęcia without it.
      final router = GoRouter.of(context);
      final active = makeupBookingScope(ref.read(authControllerProvider));
      Navigator.of(context).pop();
      router.go(
        active == widget.scope
            ? clientClassDetailsLocation(event.id)
            : clientSchedulePath,
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

/// The dark bar of the make-up mode, with a way out on the right.
class _Bar extends StatelessWidget {
  const _Bar({required this.title, this.subtitle});

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Material(
      color: c.highlight,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: EdgeInsets.fromLTRB(sp.page, sp.m, sp.xs, sp.m),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Semantics(
                  header: true,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: sp.xs,
                    children: [
                      Text(
                        title,
                        style: ct.text.button.copyWith(color: c.background),
                      ),
                      if (subtitle != null)
                        Text(
                          subtitle!,
                          style: ct.text.meta.copyWith(
                            color: c.background.withValues(alpha: 0.85),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              IconButton(
                tooltip: l10n.clientSheetClose,
                onPressed: () => Navigator.of(context).maybePop(),
                icon: Icon(Icons.close, color: c.background),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
