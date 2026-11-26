import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';
import 'package:mobile/features/schedule/data/calendar_sync_now.dart';
import 'package:mobile/features/schedule/data/phone_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens an address outside the app; replaced in tests.
typedef ExternalLauncher = Future<bool> Function(Uri uri);

final externalLauncherProvider = Provider<ExternalLauncher>(
  (ref) =>
      (uri) => launchUrl(uri, mode: LaunchMode.externalApplication),
);

/// Syncing the account's schedule with an external calendar (Google Calendar,
/// Apple Calendar), opened from "Mój grafik": right away, by writing it to a
/// calendar on the phone, and automatically, by subscribing the calendar to
/// the account's private feed link.
class CalendarSyncScreen extends ConsumerWidget {
  const CalendarSyncScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: Text(AppLocalizations.of(context).calendarSyncTitle),
      ),
      body: switch (ref.watch(authControllerProvider)) {
        Authenticated(:final session) => _CalendarSync(
          key: ValueKey(session.accountId),
          session: session,
        ),
        _ => const SizedBox(),
      },
    );
  }
}

/// The app bar button of "Mój grafik" that opens [CalendarSyncScreen].
class CalendarSyncAction extends StatelessWidget {
  const CalendarSyncAction({super.key});

  @override
  Widget build(BuildContext context) {
    return IconButton(
      key: const Key('calendarSyncAction'),
      tooltip: AppLocalizations.of(context).calendarSyncOpen,
      icon: const Icon(Icons.sync_alt),
      onPressed: () => Navigator.of(context).push<void>(
        MaterialPageRoute(builder: (_) => const CalendarSyncScreen()),
      ),
    );
  }
}

class _CalendarSync extends ConsumerStatefulWidget {
  const _CalendarSync({required this.session, super.key});

  final Session session;

  @override
  ConsumerState<_CalendarSync> createState() => _CalendarSyncState();
}

class _CalendarSyncState extends ConsumerState<_CalendarSync> {
  bool _busy = false;

  CalendarFeedController get _controller =>
      ref.read(calendarFeedProvider(widget.session.accountId).notifier);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final provider = calendarFeedProvider(widget.session.accountId);
    final titleStyle = Theme.of(context).textTheme.titleMedium;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _SyncNow(session: widget.session),
        const Divider(height: 48),
        Text(l10n.calendarSyncLinkSection, style: titleStyle),
        const SizedBox(height: 8),
        Text(l10n.calendarSyncIntro),
        const SizedBox(height: 8),
        Text(
          l10n.calendarSyncRefreshNote,
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 24),
        ...ref
            .watch(provider)
            .when(
              skipLoadingOnRefresh: false,
              loading: () => const [Center(child: CircularProgressIndicator())],
              error: (_, _) => [
                Text(l10n.calendarSyncLoadError),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton(
                    onPressed: () => ref.invalidate(provider),
                    child: Text(l10n.passesRetryButton),
                  ),
                ),
              ],
              data: (state) => _actions(l10n, state),
            ),
      ],
    );
  }

  List<Widget> _actions(AppLocalizations l10n, CalendarFeedState state) {
    if (!state.active) {
      return [
        FilledButton.icon(
          key: const Key('calendarSyncEnable'),
          onPressed: _busy ? null : () => _run(_controller.create),
          icon: const Icon(Icons.sync),
          label: Text(l10n.calendarSyncEnable),
        ),
      ];
    }
    final link = state.link;
    return [
      if (link == null)
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Text(l10n.calendarSyncOtherDevice),
          ),
        )
      else ...[
        Text(
          l10n.calendarSyncActiveSince(
            DateFormat(
              'dd.MM.yyyy, HH:mm',
              'pl',
            ).format(link.createdAt.toLocal()),
          ),
          style: Theme.of(context).textTheme.labelMedium,
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          key: const Key('calendarSyncGoogle'),
          onPressed: _busy
              ? null
              : () => _open(link, (scope) => link.googleFor(scope)),
          icon: const Icon(Icons.calendar_month),
          label: Text(l10n.calendarSyncAddGoogle),
        ),
        const SizedBox(height: 8),
        FilledButton.icon(
          key: const Key('calendarSyncApple'),
          onPressed: _busy
              ? null
              : () => _open(link, (scope) => Uri.parse(link.webcalFor(scope))),
          icon: const Icon(Icons.event),
          label: Text(l10n.calendarSyncAddApple),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const Key('calendarSyncCopy'),
          onPressed: _busy ? null : () => _copy(link),
          icon: const Icon(Icons.copy),
          label: Text(l10n.calendarSyncCopy),
        ),
      ],
      const SizedBox(height: 24),
      OutlinedButton.icon(
        key: const Key('calendarSyncRegenerate'),
        onPressed: _busy ? null : _regenerate,
        icon: const Icon(Icons.refresh),
        label: Text(l10n.calendarSyncRegenerate),
      ),
      const SizedBox(height: 8),
      TextButton.icon(
        key: const Key('calendarSyncTurnOff'),
        onPressed: _busy ? null : _turnOff,
        icon: const Icon(Icons.sync_disabled),
        label: Text(l10n.calendarSyncTurnOff),
      ),
    ];
  }

  Future<CalendarFeedScope?> _chooseScope() =>
      chooseCalendarScope(context, widget.session);

  Future<void> _open(
    CalendarFeedLink link,
    Uri Function(CalendarFeedScope scope) address,
  ) async {
    final scope = await _chooseScope();
    if (scope == null || !mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    final failed = AppLocalizations.of(context).calendarSyncOpenFailed;
    var opened = false;
    try {
      opened = await ref.read(externalLauncherProvider)(address(scope));
    } catch (_) {
      opened = false;
    }
    if (!opened) messenger.showSnackBar(SnackBar(content: Text(failed)));
  }

  Future<void> _copy(CalendarFeedLink link) async {
    final scope = await _chooseScope();
    if (scope == null || !mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    final copied = AppLocalizations.of(context).calendarSyncCopied;
    await Clipboard.setData(ClipboardData(text: link.httpsFor(scope)));
    messenger.showSnackBar(SnackBar(content: Text(copied)));
  }

  Future<void> _regenerate() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await _confirm(
      l10n.calendarSyncRegenerateTitle,
      l10n.calendarSyncRegenerateBody,
      l10n.calendarSyncRegenerateConfirm,
    );
    if (confirmed) await _run(_controller.create);
  }

  Future<void> _turnOff() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await _confirm(
      l10n.calendarSyncTurnOffTitle,
      l10n.calendarSyncTurnOffBody,
      l10n.calendarSyncTurnOffConfirm,
    );
    if (confirmed) await _run(_controller.turnOff);
  }

  Future<bool> _confirm(String title, String body, String action) =>
      _confirmDialog(context, title, body, action);

  Future<void> _run(Future<void> Function() action) async {
    final messenger = ScaffoldMessenger.of(context);
    final failed = AppLocalizations.of(context).calendarSyncError;
    setState(() => _busy = true);
    try {
      await action();
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(failed)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// An account that is both a client and a trainer chooses what to add to a
/// calendar; any other account adds its whole schedule. `null` when the user
/// closes the sheet.
Future<CalendarFeedScope?> chooseCalendarScope(
  BuildContext context,
  Session session,
) async {
  final roles = session.roles;
  if (!(roles.contains(AppRole.client) && roles.contains(AppRole.trainer))) {
    return CalendarFeedScope.all;
  }
  final l10n = AppLocalizations.of(context);
  return showModalBottomSheet<CalendarFeedScope>(
    context: context,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            title: Text(
              l10n.calendarSyncScopeTitle,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          for (final (scope, title, hint) in [
            (
              CalendarFeedScope.all,
              l10n.calendarSyncScopeAll,
              l10n.calendarSyncScopeAllHint,
            ),
            (
              CalendarFeedScope.client,
              l10n.calendarSyncScopeClient,
              l10n.calendarSyncScopeClientHint,
            ),
            (
              CalendarFeedScope.trainer,
              l10n.calendarSyncScopeTrainer,
              l10n.calendarSyncScopeTrainerHint,
            ),
          ])
            ListTile(
              key: Key('calendarSyncScope.${scope.name}'),
              title: Text(title),
              subtitle: Text(hint),
              onTap: () => Navigator.of(context).pop(scope),
            ),
        ],
      ),
    ),
  );
}

Future<bool> _confirmDialog(
  BuildContext context,
  String title,
  String body,
  String action,
) async {
  final l10n = AppLocalizations.of(context);
  final result = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: Text(body),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.calendarSyncCancel),
        ),
        TextButton(
          key: const Key('calendarSyncConfirm'),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(action),
        ),
      ],
    ),
  );
  return result ?? false;
}

/// "Synchronizuj teraz": writes the schedule to a calendar on the phone at
/// once. The first sync asks what to add (an account with both roles) and
/// which calendar to use; later ones reuse both.
class _SyncNow extends ConsumerStatefulWidget {
  const _SyncNow({required this.session});

  final Session session;

  @override
  ConsumerState<_SyncNow> createState() => _SyncNowState();
}

class _SyncNowState extends ConsumerState<_SyncNow> {
  /// Buttons are off while a sync or a removal is under way.
  bool _busy = false;

  /// Events are being written; not while the user picks in a sheet.
  bool _writing = false;

  CalendarSyncNowController get _controller =>
      ref.read(calendarSyncNowProvider(widget.session.accountId).notifier);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final record = ref
        .watch(calendarSyncNowProvider(widget.session.accountId))
        .value;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          l10n.calendarSyncNowSection,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(l10n.calendarSyncNowIntro),
        if (record != null) ...[
          const SizedBox(height: 8),
          Text(
            l10n.calendarSyncNowLast(
              record.calendarName,
              DateFormat(
                'dd.MM.yyyy, HH:mm',
                'pl',
              ).format(record.syncedAt.toLocal()),
            ),
            key: const Key('calendarSyncNowLast'),
            style: Theme.of(context).textTheme.labelMedium,
          ),
        ],
        const SizedBox(height: 12),
        FilledButton.icon(
          key: const Key('calendarSyncNow'),
          onPressed: _busy ? null : () => _sync(choose: record == null),
          icon: _writing
              ? const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.sync),
          label: Text(l10n.calendarSyncNow),
        ),
        if (record != null)
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                key: const Key('calendarSyncNowChange'),
                onPressed: _busy ? null : () => _sync(choose: true),
                child: Text(l10n.calendarSyncNowChange),
              ),
              TextButton(
                key: const Key('calendarSyncNowRemove'),
                onPressed: _busy ? null : () => _remove(record),
                child: Text(l10n.calendarSyncNowRemove),
              ),
            ],
          ),
      ],
    );
  }

  /// Syncs; with [choose], first asks what to add and to which calendar.
  Future<void> _sync({required bool choose}) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final phone = ref.read(phoneCalendarProvider);
    setState(() => _busy = true);
    try {
      if (!await phone.requestAccess()) {
        messenger.showSnackBar(
          SnackBar(
            content: Text(l10n.calendarSyncNowNoAccess),
            action: SnackBarAction(
              label: l10n.calendarSyncNowOpenSettings,
              onPressed: phone.openSettings,
            ),
          ),
        );
        return;
      }
      final calendars = await phone.writableCalendars();
      final record = await ref.read(
        calendarSyncNowProvider(widget.session.accountId).future,
      );
      // A calendar removed from the phone since the last sync is chosen again.
      final keepsCalendar =
          !choose && calendars.any((c) => c.id == record?.calendarId);
      CalendarFeedScope? scope;
      PhoneCalendarInfo? calendar;
      if (!keepsCalendar) {
        if (calendars.isEmpty) {
          messenger.showSnackBar(
            SnackBar(content: Text(l10n.calendarSyncNowNoCalendars)),
          );
          return;
        }
        if (!mounted) return;
        scope = await chooseCalendarScope(context, widget.session);
        if (scope == null || !mounted) return;
        calendar = await _chooseCalendar(calendars);
        if (calendar == null) return;
      }
      if (mounted) setState(() => _writing = true);
      final result = await _controller.sync(
        cancelledPrefix: l10n.calendarSyncNowCancelledPrefix,
        calendar: calendar,
        scope: scope,
      );
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            result.unchanged
                ? l10n.calendarSyncNowUpToDate
                : l10n.calendarSyncNowDone(
                    result.added,
                    result.updated,
                    result.removed,
                  ),
          ),
        ),
      );
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(l10n.calendarSyncError)));
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _writing = false;
        });
      }
    }
  }

  Future<PhoneCalendarInfo?> _chooseCalendar(
    List<PhoneCalendarInfo> calendars,
  ) {
    final l10n = AppLocalizations.of(context);
    return showModalBottomSheet<PhoneCalendarInfo>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.7,
          ),
          child: ListView(
            shrinkWrap: true,
            children: [
              ListTile(
                title: Text(
                  l10n.calendarSyncNowPickTitle,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                subtitle: Text(l10n.calendarSyncNowPickHint),
              ),
              for (final calendar in calendars)
                ListTile(
                  key: Key('calendarSyncNowCalendar.${calendar.id}'),
                  leading: const Icon(Icons.calendar_today),
                  title: Text(calendar.name),
                  subtitle:
                      calendar.accountName == null ||
                          calendar.accountName == calendar.name
                      ? null
                      : Text(calendar.accountName!),
                  onTap: () => Navigator.of(context).pop(calendar),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _remove(CalendarSyncRecord record) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await _confirmDialog(
      context,
      l10n.calendarSyncNowRemoveTitle,
      l10n.calendarSyncNowRemoveBody(record.calendarName),
      l10n.calendarSyncNowRemoveConfirm,
    );
    if (!confirmed || !mounted) return;
    setState(() => _busy = true);
    try {
      if (!await ref.read(phoneCalendarProvider).requestAccess()) {
        messenger.showSnackBar(
          SnackBar(content: Text(l10n.calendarSyncNowNoAccess)),
        );
        return;
      }
      final removed = await _controller.removeAll();
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.calendarSyncNowRemoved(removed))),
      );
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(l10n.calendarSyncError)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
