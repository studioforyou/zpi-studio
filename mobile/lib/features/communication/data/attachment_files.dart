import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gal/gal.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';

/// What happened when a file was handed to another app.
enum OpenOutcome {
  opened,

  /// No app on the phone opens this kind of file.
  noApp,
  failed,
}

/// What happened when a photo was saved in the phone's gallery.
enum GallerySave {
  saved,

  /// The user did not let the app add photos.
  denied,

  /// The phone's gallery does not take this kind of image, e.g. HEIC on an
  /// older Android.
  unsupported,
  failed,
}

/// Most the downloaded attachments may take up on the phone: 300 MB.
const attachmentCacheLimit = 300 * 1024 * 1024;

/// Files attached to messages, downloaded once into the app's cache: a file
/// never changes, so it is kept by its id. Photos are shown from there, other
/// files are opened in an app of the phone.
///
/// The cache keeps at most [cacheLimit] bytes: when a download takes it over,
/// the files used longest ago are removed until it is at five sixths of it,
/// so that it is not done again after the next download.
class AttachmentFiles {
  AttachmentFiles(
    this._api, {
    Future<Directory> Function()? directory,
    this.cacheLimit = attachmentCacheLimit,
  }) : _directory = directory ?? getApplicationCacheDirectory;

  final ConversationsApi _api;
  final Future<Directory> Function() _directory;
  final int cacheLimit;

  /// Downloads in progress, so a file shown twice is fetched once.
  final _pending = <String, Future<File>>{};

  /// How many bytes the cache takes up: counted once, then kept up to date.
  int _cacheBytes = 0;
  Future<void>? _counted;
  Future<void>? _trimming;

  /// The attachment's file, downloaded if it is not in the cache yet.
  Future<File> file(
    String conversationId,
    String messageId,
    ChatAttachment attachment, {
    void Function(double progress)? onProgress,
  }) {
    return _pending[attachment.id] ??=
        _fetch(conversationId, messageId, attachment, onProgress).whenComplete(
          // A block, not `=> remove(...)`: remove returns this very future,
          // and whenComplete would wait for it, so it would never complete.
          () {
            _pending.remove(attachment.id);
          },
        );
  }

  /// What is shown of a photo in a conversation or a list: its thumbnail
  /// when it has one, otherwise the whole photo.
  Future<File> preview(
    String conversationId,
    String messageId,
    ChatAttachment photo,
  ) {
    if (!photo.hasThumbnail) {
      return file(conversationId, messageId, photo);
    }
    return _pending['thumbnail:${photo.id}'] ??=
        _fetchThumbnail(conversationId, messageId, photo).whenComplete(() {
          _pending.remove('thumbnail:${photo.id}');
        });
  }

  /// The attachment's file if it is already in the cache.
  Future<File?> cached(ChatAttachment attachment) async {
    final file = await _location(attachment);
    return await _isComplete(file, attachment) ? file : null;
  }

  /// The photo's thumbnail if it has one and it is already in the cache.
  Future<File?> cachedPreview(ChatAttachment photo) async {
    if (!photo.hasThumbnail) return null;
    final file = await _thumbnailLocation(photo);
    return await file.exists() ? file : null;
  }

  Future<File> _fetch(
    String conversationId,
    String messageId,
    ChatAttachment attachment,
    void Function(double progress)? onProgress,
  ) async {
    final file = await _location(attachment);
    if (await _isComplete(file, attachment)) {
      return _used(file);
    }
    return _download(
      file,
      (path) => _api.downloadAttachment(
        conversationId,
        messageId,
        attachment.id,
        path,
        onProgress: onProgress,
      ),
    );
  }

  Future<File> _fetchThumbnail(
    String conversationId,
    String messageId,
    ChatAttachment photo,
  ) async {
    final file = await _thumbnailLocation(photo);
    // Saved only once it is complete, so one there is whole.
    if (await file.exists()) {
      return _used(file);
    }
    return _download(
      file,
      (path) => _api.downloadAttachment(
        conversationId,
        messageId,
        photo.id,
        path,
        thumbnail: true,
      ),
    );
  }

  Future<File> _download(
    File file,
    Future<void> Function(String path) download,
  ) async {
    // Counted before anything is added, so nothing is counted twice.
    await (_counted ??= _count());
    await file.parent.create(recursive: true);
    // Downloaded next to it and renamed, so a broken download is never shown.
    final partial = File('${file.path}.part');
    await download(partial.path);
    final saved = await partial.rename(file.path);
    _cacheBytes += await saved.length();
    if (_cacheBytes > cacheLimit) {
      // Rare: once in a while, after downloads of a sixth of the limit.
      await (_trimming ??= _trim().whenComplete(() {
        _trimming = null;
      }));
    }
    return saved;
  }

  /// Notes that the file was used now: the files used longest ago are the
  /// first to go when the cache is full.
  static Future<File> _used(File file) async {
    try {
      await file.setLastModified(DateTime.now());
    } catch (_) {
      // Then it only goes a little sooner.
    }
    return file;
  }

  Future<void> _count() async {
    _cacheBytes = 0;
    for (final (_, stat) in await _cachedFiles()) {
      _cacheBytes += stat.size;
    }
  }

  /// Removes the files used longest ago until the cache is at five sixths of
  /// [cacheLimit].
  Future<void> _trim() async {
    final files = await _cachedFiles();
    files.sort((a, b) => a.$2.modified.compareTo(b.$2.modified));
    var total = files.fold(0, (sum, entry) => sum + entry.$2.size);
    final target = cacheLimit * 5 ~/ 6;
    for (final (file, stat) in files) {
      if (total <= target) break;
      try {
        await file.delete();
        total -= stat.size;
        // An attachment's own folder goes with its file; a folder that still
        // has files is not deleted.
        await file.parent.delete();
      } catch (_) {
        // Gone already, or the folder is not empty.
      }
    }
    _cacheBytes = total;
  }

  /// The downloaded files, without the ones still downloading.
  Future<List<(File, FileStat)>> _cachedFiles() async {
    final root = Directory('${(await _directory()).path}/attachments');
    final files = <(File, FileStat)>[];
    if (!await root.exists()) return files;
    await for (final entity in root.list(recursive: true, followLinks: false)) {
      if (entity is File && !entity.path.endsWith('.part')) {
        files.add((entity, await entity.stat()));
      }
    }
    return files;
  }

  /// Kept under its own name, so the app that opens it shows that name.
  Future<File> _location(ChatAttachment attachment) async {
    final root = await _directory();
    final name = attachment.fileName.replaceAll(RegExp(r'[/\\]'), '_');
    return File('${root.path}/attachments/${attachment.id}/$name');
  }

  Future<File> _thumbnailLocation(ChatAttachment photo) async {
    final root = await _directory();
    return File('${root.path}/attachments/thumbnails/${photo.id}.jpg');
  }

  static Future<bool> _isComplete(File file, ChatAttachment attachment) async =>
      await file.exists() && await file.length() == attachment.sizeBytes;

  /// Opens the file in an app of the phone that handles its type: a PDF
  /// viewer, Word, Excel, or the system preview on iPhone.
  Future<OpenOutcome> open(File file, String contentType) async {
    final result = await OpenFilex.open(file.path, type: contentType);
    return switch (result.type) {
      ResultType.done => OpenOutcome.opened,
      ResultType.noAppToOpen => OpenOutcome.noApp,
      _ => OpenOutcome.failed,
    };
  }

  /// Saves a photo in the phone's gallery: Photos on iPhone (asking once for
  /// permission to add photos), the Pictures album on Android.
  Future<GallerySave> saveToGallery(File file) async {
    try {
      await Gal.putImage(file.path);
      return GallerySave.saved;
    } on GalException catch (e) {
      return switch (e.type) {
        GalExceptionType.permissionDenied => GallerySave.denied,
        GalExceptionType.notSupportedFormat => GallerySave.unsupported,
        _ => GallerySave.failed,
      };
    }
  }

  /// Lets the user choose where on the phone to save a copy. Returns whether
  /// it was saved (`false` when the user cancelled).
  Future<bool> save(File file, ChatAttachment attachment) async {
    final saved = await FilePicker.saveFile(
      fileName: attachment.fileName,
      bytes: await file.readAsBytes(),
      mimeType: attachment.contentType,
    );
    return saved != null;
  }
}

final attachmentFilesProvider = Provider<AttachmentFiles>(
  (ref) => AttachmentFiles(ref.watch(conversationsApiProvider)),
);
