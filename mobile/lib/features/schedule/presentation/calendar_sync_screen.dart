import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens an address outside the app; replaced in tests.
typedef ExternalLauncher = Future<bool> Function(Uri uri);

final externalLauncherProvider = Provider<ExternalLauncher>(
  (ref) =>
      (uri) => launchUrl(uri, mode: LaunchMode.externalApplication),
);

/// The account's subscription of an external calendar (Google Calendar, Apple
/// Calendar) to its schedule, opened from "Mój grafik".
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
    return ref
        .watch(provider)
        .when(
          skipLoadingOnRefresh: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.calendarSyncLoadError),
                TextButton(
                  onPressed: () => ref.invalidate(provider),
                  child: Text(l10n.passesRetryButton),
                ),
              ],
            ),
          ),
          data: (state) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(l10n.calendarSyncIntro),
              const SizedBox(height: 8),
              Text(
                l10n.calendarSyncRefreshNote,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 24),
              ..._actions(l10n, state),
            ],
          ),
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

  /// An account that is both a client and a trainer chooses what to add.
  Future<CalendarFeedScope?> _chooseScope() async {
    final roles = widget.session.roles;
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

  Future<bool> _confirm(String title, String body, String action) async {
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
