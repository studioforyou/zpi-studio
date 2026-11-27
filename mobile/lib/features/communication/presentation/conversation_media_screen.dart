import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/widgets/message_attachments.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// How many photos or files are loaded at a time.
const mediaPageSize = 60;

/// More are loaded when the user scrolls this close to the end.
const _loadMoreExtent = 600.0;

/// Every photo sent in a conversation, newest first, by month. A photo opens
/// full screen, where the user swipes through all of them.
class ConversationPhotosScreen extends ConsumerStatefulWidget {
  const ConversationPhotosScreen({
    super.key,
    required this.conversationId,
    required this.title,
  });

  final String conversationId;

  /// The conversation's title, shown under the screen's.
  final String title;

  @override
  ConsumerState<ConversationPhotosScreen> createState() =>
      _ConversationPhotosScreenState();
}

class _ConversationPhotosScreenState
    extends _AttachmentListState<ConversationPhotosScreen> {
  @override
  AttachmentKind get kind => AttachmentKind.photo;

  @override
  String get conversationId => widget.conversationId;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return _screen(
      context,
      title: l10n.chatPhotos,
      subtitle: widget.title,
      emptyIcon: Icons.photo_library_outlined,
      emptyText: l10n.chatNoPhotos,
      section: (start, end) => SliverGrid.builder(
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 140,
          mainAxisSpacing: 2,
          crossAxisSpacing: 2,
        ),
        itemCount: end - start,
        itemBuilder: (context, offset) {
          final index = start + offset;
          final item = items[index];
          return PhotoThumbnail(
            key: ValueKey(item.attachment.id),
            conversationId: widget.conversationId,
            messageId: item.messageId,
            photo: item.attachment,
            onTap: () => openPhotoViewer(
              context,
              conversationId: widget.conversationId,
              photos: items,
              initialIndex: index,
              hasOlder: hasMore,
              // As in the grid: the next photo is the older one.
              olderToTheRight: true,
            ),
          );
        },
      ),
    );
  }
}

/// Every file other than photos sent in a conversation, newest first, by
/// month. A file opens in an app of the phone.
class ConversationFilesScreen extends ConsumerStatefulWidget {
  const ConversationFilesScreen({
    super.key,
    required this.conversationId,
    required this.title,
  });

  final String conversationId;

  /// The conversation's title, shown under the screen's.
  final String title;

  @override
  ConsumerState<ConversationFilesScreen> createState() =>
      _ConversationFilesScreenState();
}

class _ConversationFilesScreenState
    extends _AttachmentListState<ConversationFilesScreen> {
  @override
  AttachmentKind get kind => AttachmentKind.file;

  @override
  String get conversationId => widget.conversationId;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return _screen(
      context,
      title: l10n.chatFiles,
      subtitle: widget.title,
      emptyIcon: Icons.insert_drive_file_outlined,
      emptyText: l10n.chatNoFiles,
      section: (start, end) => SliverList.builder(
        itemCount: end - start,
        itemBuilder: (context, offset) {
          final item = items[start + offset];
          return AttachmentFileRow(
            key: ValueKey(item.attachment.id),
            conversationId: widget.conversationId,
            item: item,
          );
        },
      ),
    );
  }
}

/// Loads a conversation's photos or files page by page, newest first, as the
/// user scrolls.
abstract class _AttachmentListState<W extends ConsumerStatefulWidget>
    extends ConsumerState<W> {
  AttachmentKind get kind;
  String get conversationId;

  final _scroll = ScrollController();

  /// Newest first.
  List<ConversationAttachment> items = const [];

  /// Whether there are older ones than the last of [items].
  bool hasMore = false;
  bool _loading = true;
  Object? _error;
  bool _loadingMore = false;
  bool _moreFailed = false;

  ConversationsApi get _api => ref.read(conversationsApiProvider);

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.position.extentAfter < _loadMoreExtent && !_moreFailed) {
        _loadMore();
      }
    });
    _load();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await _api.fetchAttachments(
        conversationId,
        kind,
        limit: mediaPageSize,
      );
      if (!mounted) return;
      setState(() {
        items = page.items.reversed.toList();
        hasMore = page.hasMore;
        _loading = false;
      });
      _fillScreen();
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _loading = false;
        });
      }
    }
  }

  Future<void> _loadMore() async {
    if (!hasMore || _loadingMore || items.isEmpty) return;
    setState(() {
      _loadingMore = true;
      _moreFailed = false;
    });
    try {
      final page = await _api.fetchAttachments(
        conversationId,
        kind,
        before: items.last.attachment.id,
        limit: mediaPageSize,
      );
      if (!mounted) return;
      setState(() {
        items = [...items, ...page.items.reversed];
        hasMore = page.hasMore;
      });
      _fillScreen();
    } catch (_) {
      if (mounted) setState(() => _moreFailed = true);
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  /// Loads more while the loaded ones do not fill the screen: there is
  /// nothing to scroll that would load them.
  void _fillScreen() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted &&
          _scroll.hasClients &&
          _scroll.position.extentAfter < _loadMoreExtent) {
        _loadMore();
      }
    });
  }

  Widget _screen(
    BuildContext context, {
    required String title,
    required String subtitle,
    required IconData emptyIcon,
    required String emptyText,
    required Widget Function(int start, int end) section,
  }) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final error = _error;
    final Widget body;
    if (_loading) {
      body = const Center(child: CircularProgressIndicator());
    } else if (error != null) {
      body = _Message(
        icon: Icons.error_outline,
        text: messagesErrorMessage(error, l10n, loading: true),
        action: OutlinedButton.icon(
          onPressed: _load,
          icon: const Icon(Icons.refresh),
          label: Text(l10n.messagesRetry),
        ),
      );
    } else if (items.isEmpty) {
      body = _Message(icon: emptyIcon, text: emptyText);
    } else {
      final now = DateTime.now();
      body = CustomScrollView(
        controller: _scroll,
        slivers: [
          for (final (start, end) in _months())
            SliverMainAxisGroup(
              slivers: [
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Text(
                      _monthLabel(items[start].sentAt, now),
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                ),
                section(start, end),
              ],
            ),
          SliverSafeArea(
            top: false,
            sliver: SliverToBoxAdapter(child: _footer(l10n)),
          ),
        ],
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title),
            Text(
              subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
      body: body,
    );
  }

  Widget _footer(AppLocalizations l10n) {
    if (_moreFailed && !_loadingMore) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Center(
          child: TextButton.icon(
            onPressed: _loadMore,
            icon: const Icon(Icons.refresh),
            label: Text(l10n.chatMediaLoadMoreError),
          ),
        ),
      );
    }
    if (hasMore) {
      return const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    return const SizedBox(height: 16);
  }

  /// Where each month starts and ends in [items], newest first.
  List<(int, int)> _months() {
    final months = <(int, int)>[];
    var start = 0;
    for (var i = 1; i <= items.length; i++) {
      if (i == items.length ||
          !_sameMonth(items[i].sentAt, items[start].sentAt)) {
        months.add((start, i));
        start = i;
      }
    }
    return months;
  }

  static bool _sameMonth(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month;

  /// `Listopad`, or `Listopad 2025` in another year.
  static String _monthLabel(DateTime time, DateTime now) {
    final label = DateFormat(
      time.year == now.year ? 'LLLL' : 'LLLL y',
      'pl',
    ).format(time);
    return label[0].toUpperCase() + label.substring(1);
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.text, this.action});

  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            Icon(icon, size: 40, color: theme.colorScheme.onSurfaceVariant),
            Text(
              text,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyLarge?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            ?action,
          ],
        ),
      ),
    );
  }
}
