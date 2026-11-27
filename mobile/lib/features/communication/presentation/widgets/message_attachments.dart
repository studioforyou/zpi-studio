import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/communication/data/attachment_files.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Widest a photo is shown in the conversation.
const maxPhotoWidth = 280.0;

/// Space between photos of one message.
const _photoGap = 3.0;

/// The files attached to a message: photos as pictures that open full screen,
/// other files as tiles that open in an app of the phone.
class MessageAttachments extends StatelessWidget {
  const MessageAttachments({
    super.key,
    required this.conversationId,
    required this.message,
    required this.maxWidth,
    required this.onLongPress,
    required this.onOpenPhoto,
  });

  final String conversationId;
  final ChatMessage message;

  /// Widest the attachments may be, as wide as a message bubble.
  final double maxWidth;
  final ValueChanged<Offset> onLongPress;

  /// Opens a photo full screen, among the other photos of the conversation.
  final ValueChanged<ChatAttachment> onOpenPhoto;

  @override
  Widget build(BuildContext context) {
    final photos = [
      for (final attachment in message.attachments)
        if (attachment.isImage) attachment,
    ];
    final files = [
      for (final attachment in message.attachments)
        if (!attachment.isImage) attachment,
    ];
    return Column(
      crossAxisAlignment: message.isMine
          ? CrossAxisAlignment.end
          : CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      spacing: 4,
      children: [
        if (photos.isNotEmpty)
          GestureDetector(
            onLongPressStart: (details) => onLongPress(details.globalPosition),
            child: _PhotoGrid(
              conversationId: conversationId,
              messageId: message.id,
              photos: photos,
              width: math.min(maxWidth, maxPhotoWidth),
              onOpen: onOpenPhoto,
            ),
          ),
        for (final file in files)
          ConstrainedBox(
            constraints: BoxConstraints(maxWidth: maxWidth),
            child: AttachmentFileTile(
              conversationId: conversationId,
              messageId: message.id,
              attachment: file,
              mine: message.isMine,
              onLongPress: onLongPress,
            ),
          ),
      ],
    );
  }
}

/// One photo at its own proportions, or several as a grid of two columns
/// (an odd last one across both).
class _PhotoGrid extends StatelessWidget {
  const _PhotoGrid({
    required this.conversationId,
    required this.messageId,
    required this.photos,
    required this.width,
    required this.onOpen,
  });

  final String conversationId;
  final String messageId;
  final List<ChatAttachment> photos;
  final double width;
  final ValueChanged<ChatAttachment> onOpen;

  @override
  Widget build(BuildContext context) {
    Widget tile(int index, double tileWidth, double tileHeight) => SizedBox(
      width: tileWidth,
      height: tileHeight,
      child: PhotoThumbnail(
        conversationId: conversationId,
        messageId: messageId,
        photo: photos[index],
        onTap: () => onOpen(photos[index]),
      ),
    );

    final Widget content;
    if (photos.length == 1) {
      final photo = photos.single;
      final ratio = (photo.width != null && photo.height != null)
          ? (photo.width! / photo.height!).clamp(0.6, 1.8)
          : 4 / 3;
      content = tile(0, width, width / ratio);
    } else {
      final side = (width - _photoGap) / 2;
      content = Wrap(
        spacing: _photoGap,
        runSpacing: _photoGap,
        children: [
          for (var i = 0; i < photos.length; i++)
            photos.length.isOdd && i == photos.length - 1
                ? tile(i, width, side * 0.75)
                : tile(i, side, side),
        ],
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: SizedBox(width: width, child: content),
    );
  }
}

/// A photo of a message, downloaded into the cache when it is first shown:
/// its thumbnail when it has one, otherwise the whole photo.
class PhotoThumbnail extends ConsumerStatefulWidget {
  const PhotoThumbnail({
    super.key,
    required this.conversationId,
    required this.messageId,
    required this.photo,
    required this.onTap,
  });

  final String conversationId;
  final String messageId;
  final ChatAttachment photo;
  final VoidCallback onTap;

  @override
  ConsumerState<PhotoThumbnail> createState() => _PhotoThumbnailState();
}

class _PhotoThumbnailState extends ConsumerState<PhotoThumbnail> {
  late Future<File> _file = _load();

  Future<File> _load() => ref
      .read(attachmentFilesProvider)
      .preview(widget.conversationId, widget.messageId, widget.photo);

  @override
  void didUpdateWidget(PhotoThumbnail oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.photo.id != widget.photo.id) {
      _file = _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Semantics(
      image: true,
      button: true,
      label: AppLocalizations.of(context).chatPhoto,
      child: LayoutBuilder(
        builder: (context, constraints) => FutureBuilder<File>(
          future: _file,
          builder: (context, snapshot) {
            final file = snapshot.data;
            if (snapshot.hasError) {
              return _placeholder(
                scheme,
                Icon(Icons.refresh, color: scheme.onSurfaceVariant),
                onTap: () => setState(() => _file = _load()),
              );
            }
            if (file == null) {
              return _placeholder(
                scheme,
                const SizedBox.square(
                  dimension: 22,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              );
            }
            final pixels = MediaQuery.devicePixelRatioOf(context);
            return GestureDetector(
              onTap: widget.onTap,
              child: Image.file(
                file,
                fit: BoxFit.cover,
                // Decoded at the size it is shown, not at the photo's full size.
                cacheWidth: (constraints.maxWidth * pixels).round(),
                gaplessPlayback: true,
                errorBuilder: (_, _, _) => _placeholder(
                  scheme,
                  Icon(
                    Icons.broken_image_outlined,
                    color: scheme.onSurfaceVariant,
                  ),
                  onTap: widget.onTap,
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  static Widget _placeholder(
    ColorScheme scheme,
    Widget child, {
    VoidCallback? onTap,
  }) => GestureDetector(
    onTap: onTap,
    child: ColoredBox(
      color: scheme.surfaceContainerHighest,
      child: Center(child: child),
    ),
  );
}

/// Downloads an attachment, showing how far it got, and then opens it in an
/// app of the phone or saves a copy on the phone.
mixin _FileActions<T extends ConsumerStatefulWidget> on ConsumerState<T> {
  String get conversationId;
  String get messageId;
  ChatAttachment get attachment;

  /// How much of the file has been downloaded, while it is downloading.
  double? progress;

  bool get busy => progress != null;

  Future<File?> _download(AppLocalizations l10n) async {
    setState(() => progress = 0);
    try {
      return await ref
          .read(attachmentFilesProvider)
          .file(
            conversationId,
            messageId,
            attachment,
            onProgress: (value) {
              if (mounted) setState(() => progress = value);
            },
          );
    } catch (_) {
      _snack(l10n.chatFileDownloadError);
      return null;
    } finally {
      if (mounted) setState(() => progress = null);
    }
  }

  Future<void> openFile() async {
    if (busy) return;
    final l10n = AppLocalizations.of(context);
    final file = await _download(l10n);
    if (file == null || !mounted) return;
    final files = ref.read(attachmentFilesProvider);
    final outcome = await files.open(file, attachment.contentType);
    switch (outcome) {
      case OpenOutcome.opened:
        break;
      case OpenOutcome.noApp:
        _snack(
          l10n.chatFileNoApp,
          action: SnackBarAction(label: l10n.chatSaveFile, onPressed: saveFile),
        );
      case OpenOutcome.failed:
        _snack(l10n.chatFileOpenError);
    }
  }

  Future<void> saveFile() async {
    if (busy) return;
    final l10n = AppLocalizations.of(context);
    final file = await _download(l10n);
    if (file == null || !mounted) return;
    await saveAttachment(context, ref, file, attachment, l10n);
  }

  void _snack(String text, {SnackBarAction? action}) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(text), action: action));
  }
}

/// A document attached to a message: its type, name and size. Tapping it
/// opens it in an app of the phone; the button saves a copy on the phone.
class AttachmentFileTile extends ConsumerStatefulWidget {
  const AttachmentFileTile({
    super.key,
    required this.conversationId,
    required this.messageId,
    required this.attachment,
    required this.mine,
    required this.onLongPress,
  });

  final String conversationId;
  final String messageId;
  final ChatAttachment attachment;

  /// Whether it is in the user's own message, which has the bubble colours of
  /// the user's messages.
  final bool mine;
  final ValueChanged<Offset> onLongPress;

  @override
  ConsumerState<AttachmentFileTile> createState() => _AttachmentFileTileState();
}

class _AttachmentFileTileState extends ConsumerState<AttachmentFileTile>
    with _FileActions {
  @override
  String get conversationId => widget.conversationId;

  @override
  String get messageId => widget.messageId;

  @override
  ChatAttachment get attachment => widget.attachment;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final background = widget.mine
        ? scheme.primary
        : scheme.surfaceContainerHigh;
    final foreground = widget.mine ? scheme.onPrimary : scheme.onSurface;
    final secondary = widget.mine
        ? scheme.onPrimary.withValues(alpha: 0.8)
        : scheme.onSurfaceVariant;
    final kind = _FileKind.of(attachment);

    return Material(
      color: background,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: openFile,
        child: GestureDetector(
          onLongPressStart: (details) =>
              widget.onLongPress(details.globalPosition),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 10, 4, 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              spacing: 10,
              children: [
                _FileIcon(
                  kind: kind,
                  background: widget.mine
                      ? scheme.onPrimary.withValues(alpha: 0.16)
                      : null,
                  color: widget.mine ? scheme.onPrimary : null,
                ),
                Flexible(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        attachment.fileName,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: foreground,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        '${kind.label} · ${formatFileSize(attachment.sizeBytes)}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: secondary,
                        ),
                      ),
                    ],
                  ),
                ),
                _SaveButton(
                  progress: progress,
                  color: secondary,
                  progressColor: foreground,
                  tooltip: l10n.chatSaveFile,
                  onPressed: saveFile,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A file in the list of a conversation's files: its type, name, size, who
/// sent it and when. Tapping it opens it in an app of the phone; the button
/// saves a copy on the phone.
class AttachmentFileRow extends ConsumerStatefulWidget {
  const AttachmentFileRow({
    super.key,
    required this.conversationId,
    required this.item,
  });

  final String conversationId;
  final ConversationAttachment item;

  @override
  ConsumerState<AttachmentFileRow> createState() => _AttachmentFileRowState();
}

class _AttachmentFileRowState extends ConsumerState<AttachmentFileRow>
    with _FileActions {
  @override
  String get conversationId => widget.conversationId;

  @override
  String get messageId => widget.item.messageId;

  @override
  ChatAttachment get attachment => widget.item.attachment;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final item = widget.item;
    final kind = _FileKind.of(attachment);
    final sender = item.isMine ? l10n.chatMe : item.senderName;
    final sent = formatListTime(item.sentAt, l10n, DateTime.now());
    return ListTile(
      contentPadding: const EdgeInsets.only(left: 16, right: 8),
      leading: _FileIcon(kind: kind),
      title: Text(
        attachment.fileName,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '${kind.label} · ${formatFileSize(attachment.sizeBytes)} · '
        '$sender, $sent',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: _SaveButton(
        progress: progress,
        color: scheme.onSurfaceVariant,
        progressColor: scheme.primary,
        tooltip: l10n.chatSaveFile,
        onPressed: saveFile,
      ),
      onTap: openFile,
    );
  }
}

/// The icon of a document's type in a rounded square of its colour.
class _FileIcon extends StatelessWidget {
  const _FileIcon({required this.kind, this.background, this.color});

  final _FileKind kind;

  /// Colours other than the type's, e.g. on the user's own bubble.
  final Color? background;
  final Color? color;

  @override
  Widget build(BuildContext context) => Container(
    width: 40,
    height: 40,
    decoration: BoxDecoration(
      color: background ?? kind.color.withValues(alpha: 0.14),
      borderRadius: BorderRadius.circular(10),
    ),
    child: Icon(kind.icon, color: color ?? kind.color),
  );
}

/// Saves a copy of the file on the phone; shows the download while it runs.
class _SaveButton extends StatelessWidget {
  const _SaveButton({
    required this.progress,
    required this.color,
    required this.progressColor,
    required this.tooltip,
    required this.onPressed,
  });

  final double? progress;
  final Color color;
  final Color progressColor;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final value = progress;
    return SizedBox.square(
      dimension: 40,
      child: value != null
          ? Padding(
              padding: const EdgeInsets.all(10),
              child: CircularProgressIndicator(
                value: value > 0 ? value : null,
                strokeWidth: 2.5,
                color: progressColor,
              ),
            )
          : IconButton(
              tooltip: tooltip,
              onPressed: onPressed,
              icon: Icon(Icons.download_outlined, color: color),
            ),
    );
  }
}

/// How a document is shown, by its type.
class _FileKind {
  const _FileKind(this.label, this.icon, this.color);

  factory _FileKind.of(ChatAttachment attachment) {
    final name = attachment.fileName;
    final dot = name.lastIndexOf('.');
    final extension = dot < 0 ? '' : name.substring(dot + 1).toUpperCase();
    return switch (attachment.contentType) {
      'application/pdf' => const _FileKind(
        'PDF',
        Icons.picture_as_pdf_outlined,
        Color(0xFFD93025),
      ),
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document' =>
        const _FileKind('Word', Icons.description_outlined, Color(0xFF2B579A)),
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' =>
        const _FileKind('Excel', Icons.table_chart_outlined, Color(0xFF217346)),
      'text/plain' => const _FileKind(
        'TXT',
        Icons.article_outlined,
        Color(0xFF5F6368),
      ),
      _ => _FileKind(
        extension.isEmpty ? '—' : extension,
        Icons.insert_drive_file_outlined,
        const Color(0xFF5F6368),
      ),
    };
  }

  final String label;
  final IconData icon;
  final Color color;
}

/// Lets the user save a copy of the file on the phone, and says when it is
/// saved.
Future<void> saveAttachment(
  BuildContext context,
  WidgetRef ref,
  File file,
  ChatAttachment attachment,
  AppLocalizations l10n,
) async {
  final messenger = ScaffoldMessenger.of(context);
  try {
    final saved = await ref
        .read(attachmentFilesProvider)
        .save(file, attachment);
    if (saved) {
      messenger.showSnackBar(SnackBar(content: Text(l10n.chatFileSaved)));
    }
  } catch (_) {
    messenger.showSnackBar(SnackBar(content: Text(l10n.chatFileOpenError)));
  }
}

/// Opens [photos] of a conversation full screen at [initialIndex].
///
/// [photos] are newest first; [hasOlder] says whether the conversation has
/// photos older than the last of them, which the viewer loads as the user
/// swipes to them.
Future<void> openPhotoViewer(
  BuildContext context, {
  required String conversationId,
  required List<ConversationAttachment> photos,
  required int initialIndex,
  required bool hasOlder,
}) => Navigator.of(context).push(
  // Opaque, so the screen below is not shown, and its snack bars neither.
  PageRouteBuilder<void>(
    pageBuilder: (_, _, _) => PhotoViewerScreen(
      conversationId: conversationId,
      photos: photos,
      initialIndex: initialIndex,
      hasOlder: hasOlder,
    ),
    transitionsBuilder: (_, animation, _, child) =>
        FadeTransition(opacity: animation, child: child),
  ),
);

/// The photos of a conversation full screen, the older ones to the left:
/// swiped through across all its messages, zoomed with two fingers or a
/// double tap, saved in the gallery or opened in another app. Older photos
/// are loaded as the user gets to them.
class PhotoViewerScreen extends ConsumerStatefulWidget {
  const PhotoViewerScreen({
    super.key,
    required this.conversationId,
    required this.photos,
    required this.initialIndex,
    this.hasOlder = false,
  });

  final String conversationId;

  /// Newest first.
  final List<ConversationAttachment> photos;
  final int initialIndex;

  /// Whether the conversation has photos older than the last of [photos].
  final bool hasOlder;

  @override
  ConsumerState<PhotoViewerScreen> createState() => _PhotoViewerScreenState();
}

class _PhotoViewerScreenState extends ConsumerState<PhotoViewerScreen> {
  /// Older photos are loaded when the user is this close to the oldest one.
  static const _preload = 3;

  late final _pages = PageController(initialPage: widget.initialIndex);
  late List<ConversationAttachment> _photos = widget.photos;
  late int _index = widget.initialIndex;
  late bool _hasOlder = widget.hasOlder;
  bool _loadingOlder = false;
  bool _olderFailed = false;

  /// While a photo is zoomed in, dragging moves it instead of changing pages.
  bool _zoomed = false;

  /// Whether the bar with the actions is shown; a tap hides or shows it.
  bool _chrome = true;

  @override
  void initState() {
    super.initState();
    // Opened at one of the oldest photos loaded: the ones before it come next.
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadOlderIfNear());
  }

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  /// The photo shown; `null` on the page after the oldest loaded one, while
  /// older ones are loading.
  ConversationAttachment? get _photo =>
      _index < _photos.length ? _photos[_index] : null;

  Future<void> _loadOlderIfNear() async {
    if (!_hasOlder || _loadingOlder || _index < _photos.length - _preload) {
      return;
    }
    setState(() {
      _loadingOlder = true;
      _olderFailed = false;
    });
    try {
      final page = await ref
          .read(conversationsApiProvider)
          .fetchAttachments(
            widget.conversationId,
            AttachmentKind.photo,
            before: _photos.last.attachment.id,
          );
      if (!mounted) return;
      final known = {for (final photo in _photos) photo.attachment.id};
      setState(() {
        // Added at the end: the photo shown stays where it is.
        _photos = [
          ..._photos,
          for (final photo in page.items.reversed)
            if (!known.contains(photo.attachment.id)) photo,
        ];
        _hasOlder = page.hasMore;
      });
    } catch (_) {
      if (mounted) setState(() => _olderFailed = true);
    } finally {
      if (mounted) setState(() => _loadingOlder = false);
    }
  }

  Future<File?> _file(
    ConversationAttachment photo,
    AppLocalizations l10n,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      return await ref
          .read(attachmentFilesProvider)
          .file(widget.conversationId, photo.messageId, photo.attachment);
    } catch (_) {
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.chatFileDownloadError)),
      );
      return null;
    }
  }

  /// Saves the photo in the gallery; a format the gallery does not take is
  /// saved as a file instead.
  Future<void> _save(ConversationAttachment photo) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final file = await _file(photo, l10n);
    if (file == null || !mounted) return;
    final result = await ref.read(attachmentFilesProvider).saveToGallery(file);
    switch (result) {
      case GallerySave.saved:
        messenger.showSnackBar(
          SnackBar(content: Text(l10n.chatSavedToGallery)),
        );
      case GallerySave.denied:
        messenger.showSnackBar(SnackBar(content: Text(l10n.chatGalleryDenied)));
      case GallerySave.unsupported:
        if (mounted) {
          await saveAttachment(context, ref, file, photo.attachment, l10n);
        }
      case GallerySave.failed:
        messenger.showSnackBar(SnackBar(content: Text(l10n.chatFileOpenError)));
    }
  }

  Future<void> _openElsewhere(ConversationAttachment photo) async {
    final l10n = AppLocalizations.of(context);
    final file = await _file(photo, l10n);
    if (file == null || !mounted) return;
    final outcome = await ref
        .read(attachmentFilesProvider)
        .open(file, photo.attachment.contentType);
    if (outcome != OpenOutcome.opened && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            outcome == OpenOutcome.noApp
                ? l10n.chatFileNoApp
                : l10n.chatFileOpenError,
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final photo = _photo;
    return Scaffold(
      backgroundColor: Colors.black,
      extendBodyBehindAppBar: true,
      appBar: _chrome
          ? AppBar(
              backgroundColor: Colors.black54,
              foregroundColor: Colors.white,
              title: photo == null
                  ? null
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          photo.isMine ? l10n.chatMe : photo.senderName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Text(
                          formatChainTime(photo.sentAt, l10n, DateTime.now()),
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: Colors.white70),
                        ),
                      ],
                    ),
              actions: [
                if (photo != null) ...[
                  IconButton(
                    tooltip: l10n.chatSaveToGallery,
                    onPressed: () => _save(photo),
                    icon: const Icon(Icons.download_outlined),
                  ),
                  IconButton(
                    tooltip: l10n.chatOpenInOtherApp,
                    onPressed: () => _openElsewhere(photo),
                    icon: const Icon(Icons.open_in_new),
                  ),
                ],
              ],
            )
          : null,
      body: PageView.builder(
        controller: _pages,
        // Newest first, from the right: older photos are to the left, and
        // loading more of them adds pages without moving the one shown.
        reverse: true,
        physics: _zoomed
            ? const NeverScrollableScrollPhysics()
            : const PageScrollPhysics(),
        itemCount: _photos.length + (_hasOlder ? 1 : 0),
        onPageChanged: (index) {
          setState(() {
            _index = index;
            _zoomed = false;
          });
          _loadOlderIfNear();
        },
        itemBuilder: (_, index) {
          if (index == _photos.length) {
            return Center(
              child: _olderFailed && !_loadingOlder
                  ? IconButton(
                      tooltip: l10n.messagesRetry,
                      onPressed: _loadOlderIfNear,
                      icon: const Icon(Icons.refresh, color: Colors.white),
                    )
                  : const CircularProgressIndicator(color: Colors.white),
            );
          }
          final photo = _photos[index];
          return _ZoomablePhoto(
            key: ValueKey(photo.attachment.id),
            conversationId: widget.conversationId,
            messageId: photo.messageId,
            photo: photo.attachment,
            onTap: () => setState(() => _chrome = !_chrome),
            onZoomed: (zoomed) {
              if (zoomed != _zoomed) setState(() => _zoomed = zoomed);
            },
          );
        },
      ),
    );
  }
}

class _ZoomablePhoto extends ConsumerStatefulWidget {
  const _ZoomablePhoto({
    super.key,
    required this.conversationId,
    required this.messageId,
    required this.photo,
    required this.onTap,
    required this.onZoomed,
  });

  final String conversationId;
  final String messageId;
  final ChatAttachment photo;
  final VoidCallback onTap;
  final ValueChanged<bool> onZoomed;

  @override
  ConsumerState<_ZoomablePhoto> createState() => _ZoomablePhotoState();
}

class _ZoomablePhotoState extends ConsumerState<_ZoomablePhoto>
    with SingleTickerProviderStateMixin {
  /// How far a double tap zooms in.
  static const _doubleTapScale = 2.5;

  final _transform = TransformationController();
  late final AnimationController _animation = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 220),
  )..addListener(() => _transform.value = _zoom!.value);
  Animation<Matrix4>? _zoom;
  Offset _doubleTapAt = Offset.zero;

  late Future<File> _file = _load();

  /// The thumbnail already in the cache, shown until the whole photo is.
  File? _preview;

  Future<File> _load() => ref
      .read(attachmentFilesProvider)
      .file(widget.conversationId, widget.messageId, widget.photo);

  void _loadPreview() {
    final photo = widget.photo;
    ref
        .read(attachmentFilesProvider)
        .cachedPreview(photo)
        .then(
          (file) {
            if (mounted && file != null && widget.photo.id == photo.id) {
              setState(() => _preview = file);
            }
          },
          onError: (_) {
            // Then the photo shows when it is downloaded.
          },
        );
  }

  @override
  void initState() {
    super.initState();
    _loadPreview();
    _transform.addListener(() {
      widget.onZoomed(_transform.value.getMaxScaleOnAxis() > 1.01);
    });
  }

  @override
  void didUpdateWidget(_ZoomablePhoto oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.photo.id != widget.photo.id) {
      _file = _load();
      _preview = null;
      _loadPreview();
      _transform.value = Matrix4.identity();
    }
  }

  @override
  void dispose() {
    _animation.dispose();
    _transform.dispose();
    super.dispose();
  }

  /// Zooms in at the tapped point, or back out when already zoomed in.
  void _toggleZoom() {
    final zoomedIn = _transform.value.getMaxScaleOnAxis() > 1.01;
    final Matrix4 target;
    if (zoomedIn) {
      target = Matrix4.identity();
    } else {
      final point = _doubleTapAt;
      target = Matrix4.identity()
        ..translateByDouble(
          -point.dx * (_doubleTapScale - 1),
          -point.dy * (_doubleTapScale - 1),
          0,
          1,
        )
        ..scaleByDouble(_doubleTapScale, _doubleTapScale, 1, 1);
    }
    _zoom = Matrix4Tween(
      begin: _transform.value,
      end: target,
    ).animate(CurvedAnimation(parent: _animation, curve: Curves.easeOut));
    _animation.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    final preview = _preview;
    final previewImage = preview == null
        ? null
        : SizedBox.expand(child: Image.file(preview, fit: BoxFit.contain));
    return FutureBuilder<File>(
      future: _file,
      builder: (context, snapshot) {
        final file = snapshot.data;
        if (snapshot.hasError) {
          return Center(
            child: IconButton(
              tooltip: AppLocalizations.of(context).messagesRetry,
              onPressed: () => setState(() => _file = _load()),
              icon: const Icon(Icons.refresh, color: Colors.white),
            ),
          );
        }
        if (file == null) {
          return Stack(
            fit: StackFit.expand,
            children: [
              ?previewImage,
              const Center(
                child: CircularProgressIndicator(color: Colors.white),
              ),
            ],
          );
        }
        return GestureDetector(
          onTap: widget.onTap,
          onDoubleTapDown: (details) => _doubleTapAt = details.localPosition,
          onDoubleTap: _toggleZoom,
          child: InteractiveViewer(
            transformationController: _transform,
            minScale: 1,
            maxScale: 5,
            child: SizedBox.expand(
              child: Image.file(
                file,
                fit: BoxFit.contain,
                // The thumbnail stays until the whole photo is decoded.
                frameBuilder: (_, child, frame, synchronous) =>
                    frame == null && !synchronous && previewImage != null
                    ? previewImage
                    : child,
                errorBuilder: (_, _, _) => const Center(
                  child: Icon(
                    Icons.broken_image_outlined,
                    color: Colors.white70,
                    size: 48,
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
