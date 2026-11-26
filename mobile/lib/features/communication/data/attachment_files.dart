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

/// Files attached to messages, downloaded once into the app's cache: a file
/// never changes, so it is kept by its id. Photos are shown from there, other
/// files are opened in an app of the phone.
class AttachmentFiles {
  AttachmentFiles(this._api, {Future<Directory> Function()? directory})
    : _directory = directory ?? getApplicationCacheDirectory;

  final ConversationsApi _api;
  final Future<Directory> Function() _directory;

  /// Downloads in progress, so a file shown twice is fetched once.
  final _pending = <String, Future<File>>{};

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

  /// The attachment's file if it is already in the cache.
  Future<File?> cached(ChatAttachment attachment) async {
    final file = await _location(attachment);
    return await _isComplete(file, attachment) ? file : null;
  }

  Future<File> _fetch(
    String conversationId,
    String messageId,
    ChatAttachment attachment,
    void Function(double progress)? onProgress,
  ) async {
    final file = await _location(attachment);
    if (await _isComplete(file, attachment)) {
      return file;
    }
    await file.parent.create(recursive: true);
    // Downloaded next to it and renamed, so a broken download is never shown.
    final partial = File('${file.path}.part');
    await _api.downloadAttachment(
      conversationId,
      messageId,
      attachment.id,
      partial.path,
      onProgress: onProgress,
    );
    return partial.rename(file.path);
  }

  /// Kept under its own name, so the app that opens it shows that name.
  Future<File> _location(ChatAttachment attachment) async {
    final root = await _directory();
    final name = attachment.fileName.replaceAll(RegExp(r'[/\\]'), '_');
    return File('${root.path}/attachments/${attachment.id}/$name');
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
