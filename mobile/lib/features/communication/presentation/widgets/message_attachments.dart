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
  });

  final String conversationId;
  final ChatMessage message;

  /// Widest the attachments may be, as wide as a message bubble.
  final double maxWidth;
  final ValueChanged<Offset> onLongPress;

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
  });

  final String conversationId;
  final String messageId;
  final List<ChatAttachment> photos;
  final double width;

  @override
  Widget build(BuildContext context) {
    Widget tile(int index, double tileWidth, double tileHeight) => SizedBox(
      width: tileWidth,
      height: tileHeight,
      child: PhotoThumbnail(
        conversationId: conversationId,
        messageId: messageId,
        photo: photos[index],
        onTap: () => _openViewer(context, index),
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

  void _openViewer(BuildContext context, int index) {
    Navigator.of(context).push(
      // Opaque, so the conversation below is not shown, and its snack bars
      // neither.
      PageRouteBuilder<void>(
        pageBuilder: (_, _, _) => PhotoViewerScreen(
          conversationId: conversationId,
          messageId: messageId,
          photos: photos,
          initialIndex: index,
        ),
        transitionsBuilder: (_, animation, _, child) =>
            FadeTransition(opacity: animation, child: child),
      ),
    );
  }
}

/// A photo of a message, downloaded into the cache when it is first shown.
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
      .file(widget.conversationId, widget.messageId, widget.photo);

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

class _AttachmentFileTileState extends ConsumerState<AttachmentFileTile> {
  /// How much of the file has been downloaded, while it is downloading.
  double? _progress;

  bool get _busy => _progress != null;

  Future<File?> _download(AppLocalizations l10n) async {
    setState(() => _progress = 0);
    try {
      return await ref
          .read(attachmentFilesProvider)
          .file(
            widget.conversationId,
            widget.messageId,
            widget.attachment,
            onProgress: (progress) {
              if (mounted) setState(() => _progress = progress);
            },
          );
    } catch (_) {
      _snack(l10n.chatFileDownloadError);
      return null;
    } finally {
      if (mounted) setState(() => _progress = null);
    }
  }

  Future<void> _open() async {
    if (_busy) return;
    final l10n = AppLocalizations.of(context);
    final file = await _download(l10n);
    if (file == null || !mounted) return;
    final files = ref.read(attachmentFilesProvider);
    final outcome = await files.open(file, widget.attachment.contentType);
    switch (outcome) {
      case OpenOutcome.opened:
        break;
      case OpenOutcome.noApp:
        _snack(
          l10n.chatFileNoApp,
          action: SnackBarAction(label: l10n.chatSaveFile, onPressed: _save),
        );
      case OpenOutcome.failed:
        _snack(l10n.chatFileOpenError);
    }
  }

  Future<void> _save() async {
    if (_busy) return;
    final l10n = AppLocalizations.of(context);
    final file = await _download(l10n);
    if (file == null || !mounted) return;
    await saveAttachment(context, ref, file, widget.attachment, l10n);
  }

  void _snack(String text, {SnackBarAction? action}) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(text), action: action));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final attachment = widget.attachment;
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
        onTap: _open,
        child: GestureDetector(
          onLongPressStart: (details) =>
              widget.onLongPress(details.globalPosition),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 10, 4, 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              spacing: 10,
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: widget.mine
                        ? scheme.onPrimary.withValues(alpha: 0.16)
                        : kind.color.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(
                    kind.icon,
                    color: widget.mine ? scheme.onPrimary : kind.color,
                  ),
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
                SizedBox.square(
                  dimension: 40,
                  child: _busy
                      ? Padding(
                          padding: const EdgeInsets.all(10),
                          child: CircularProgressIndicator(
                            value: (_progress ?? 0) > 0 ? _progress : null,
                            strokeWidth: 2.5,
                            color: foreground,
                          ),
                        )
                      : IconButton(
                          tooltip: l10n.chatSaveFile,
                          onPressed: _save,
                          icon: Icon(Icons.download_outlined, color: secondary),
                        ),
                ),
              ],
            ),
          ),
        ),
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

/// The photos of a message full screen: swiped through, zoomed with two
/// fingers or a double tap, saved in the gallery or opened in another app.
class PhotoViewerScreen extends ConsumerStatefulWidget {
  const PhotoViewerScreen({
    super.key,
    required this.conversationId,
    required this.messageId,
    required this.photos,
    required this.initialIndex,
  });

  final String conversationId;
  final String messageId;
  final List<ChatAttachment> photos;
  final int initialIndex;

  @override
  ConsumerState<PhotoViewerScreen> createState() => _PhotoViewerScreenState();
}

class _PhotoViewerScreenState extends ConsumerState<PhotoViewerScreen> {
  late final _pages = PageController(initialPage: widget.initialIndex);
  late int _index = widget.initialIndex;

  /// While a photo is zoomed in, dragging moves it instead of changing pages.
  bool _zoomed = false;

  /// Whether the bar with the actions is shown; a tap hides or shows it.
  bool _chrome = true;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  ChatAttachment get _photo => widget.photos[_index];

  Future<File?> _file(ChatAttachment photo, AppLocalizations l10n) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      return await ref
          .read(attachmentFilesProvider)
          .file(widget.conversationId, widget.messageId, photo);
    } catch (_) {
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.chatFileDownloadError)),
      );
      return null;
    }
  }

  /// Saves the photo in the gallery; a format the gallery does not take is
  /// saved as a file instead.
  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final photo = _photo;
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
          await saveAttachment(context, ref, file, photo, l10n);
        }
      case GallerySave.failed:
        messenger.showSnackBar(SnackBar(content: Text(l10n.chatFileOpenError)));
    }
  }

  Future<void> _openElsewhere() async {
    final l10n = AppLocalizations.of(context);
    final photo = _photo;
    final file = await _file(photo, l10n);
    if (file == null || !mounted) return;
    final outcome = await ref
        .read(attachmentFilesProvider)
        .open(file, photo.contentType);
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
    final count = widget.photos.length;
    return Scaffold(
      backgroundColor: Colors.black,
      extendBodyBehindAppBar: true,
      appBar: _chrome
          ? AppBar(
              backgroundColor: Colors.black54,
              foregroundColor: Colors.white,
              title: Text(
                count > 1
                    ? l10n.chatPhotoPosition(_index + 1, count)
                    : l10n.chatPhoto,
              ),
              actions: [
                IconButton(
                  tooltip: l10n.chatSaveToGallery,
                  onPressed: _save,
                  icon: const Icon(Icons.download_outlined),
                ),
                IconButton(
                  tooltip: l10n.chatOpenInOtherApp,
                  onPressed: _openElsewhere,
                  icon: const Icon(Icons.open_in_new),
                ),
              ],
            )
          : null,
      body: PageView.builder(
        controller: _pages,
        physics: _zoomed
            ? const NeverScrollableScrollPhysics()
            : const PageScrollPhysics(),
        itemCount: count,
        onPageChanged: (index) => setState(() {
          _index = index;
          _zoomed = false;
        }),
        itemBuilder: (_, index) => _ZoomablePhoto(
          conversationId: widget.conversationId,
          messageId: widget.messageId,
          photo: widget.photos[index],
          onTap: () => setState(() => _chrome = !_chrome),
          onZoomed: (zoomed) {
            if (zoomed != _zoomed) setState(() => _zoomed = zoomed);
          },
        ),
      ),
    );
  }
}

class _ZoomablePhoto extends ConsumerStatefulWidget {
  const _ZoomablePhoto({
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

  Future<File> _load() => ref
      .read(attachmentFilesProvider)
      .file(widget.conversationId, widget.messageId, widget.photo);

  @override
  void initState() {
    super.initState();
    _transform.addListener(() {
      widget.onZoomed(_transform.value.getMaxScaleOnAxis() > 1.01);
    });
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
          return const Center(
            child: CircularProgressIndicator(color: Colors.white),
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
