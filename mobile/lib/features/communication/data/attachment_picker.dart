import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';

/// Most attachments in one message, as the backend accepts them.
const maxAttachments = 5;

/// Largest attachment, as the backend accepts it (10 MB).
const maxAttachmentBytes = 10 * 1024 * 1024;

/// Largest total of the attachments of one message (30 MB, the backend's
/// request limit), less room for the text and the form around them.
const maxAttachmentsTotalBytes = 30 * 1024 * 1024 - 64 * 1024;

/// Photos are scaled down to this longest side before they are sent...
const maxPhotoDimension = 2560.0;

/// ...and saved as JPEG of this quality: about 0.5-1 MB, with no visible
/// difference on a phone.
const photoQuality = 85;

/// What can be picked from the phone's files: documents, and photos in JPEG
/// or PNG.
const documentExtensions = [
  'pdf',
  'doc',
  'docx',
  'xls',
  'xlsx',
  'txt',
  'jpg',
  'jpeg',
  'png',
];

/// HEIC photos are not picked from files: sent as they are, they would get
/// no thumbnail and no compression. The gallery picker sends them as JPEG.
const heicExtensions = {'heic', 'heif'};

const _imageExtensions = {'jpg', 'jpeg', 'png'};

/// Picks photos and documents to attach to a message.
class AttachmentPicker {
  AttachmentPicker({ImagePicker? images}) : _images = images ?? ImagePicker();

  final ImagePicker _images;

  /// A photo taken with the camera; empty when the user cancels.
  Future<List<OutgoingAttachment>> takePhoto() async {
    final photo = await _images.pickImage(
      source: ImageSource.camera,
      maxWidth: maxPhotoDimension,
      maxHeight: maxPhotoDimension,
      imageQuality: photoQuality,
      requestFullMetadata: false,
    );
    return photo == null ? const [] : [await _photo(photo, 0)];
  }

  /// Up to [limit] photos from the gallery.
  Future<List<OutgoingAttachment>> pickPhotos(int limit) async {
    final photos = await _images.pickMultiImage(
      maxWidth: maxPhotoDimension,
      maxHeight: maxPhotoDimension,
      imageQuality: photoQuality,
      limit: limit,
      requestFullMetadata: false,
    );
    return [for (var i = 0; i < photos.length; i++) await _photo(photos[i], i)];
  }

  /// PDF, Word, Excel and text documents (and JPEG and PNG photos) from the
  /// phone's files.
  Future<List<OutgoingAttachment>> pickDocuments() async {
    final files = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: documentExtensions,
    );
    return [
      for (final file in files)
        OutgoingAttachment(
          path: await _localPath(file),
          name: file.name,
          sizeBytes: await file.length() ?? 0,
          isImage: _isImage(file.name),
        ),
    ];
  }

  /// A picked photo, named after when it was picked, e.g.
  /// `IMG_20261126_183000.jpg`.
  static Future<OutgoingAttachment> _photo(XFile photo, int index) async {
    final dot = photo.name.lastIndexOf('.');
    final extension = dot < 0
        ? 'jpg'
        : photo.name.substring(dot + 1).toLowerCase();
    final stamp = DateFormat('yyyyMMdd_HHmmss').format(DateTime.now());
    return OutgoingAttachment(
      path: photo.path,
      name: 'IMG_$stamp${index > 0 ? '_$index' : ''}.$extension',
      sizeBytes: await photo.length(),
      isImage: true,
    );
  }

  /// The file's path, or a copy in the temporary directory when the picker
  /// gave only its content (e.g. from a cloud drive).
  static Future<String> _localPath(PlatformFile file) async {
    final path = file.path;
    if (path != null) {
      return path;
    }
    final directory = await getTemporaryDirectory();
    final copy = File(
      '${directory.path}/picked-${DateTime.now().microsecondsSinceEpoch}-'
      '${file.name.replaceAll(RegExp(r'[/\\]'), '_')}',
    );
    final sink = copy.openWrite();
    try {
      await sink.addStream(file.readAsByteStream());
    } finally {
      await sink.close();
    }
    return copy.path;
  }

  static bool _isImage(String name) {
    final dot = name.lastIndexOf('.');
    return dot >= 0 &&
        _imageExtensions.contains(name.substring(dot + 1).toLowerCase());
  }
}

final attachmentPickerProvider = Provider<AttachmentPicker>(
  (ref) => AttachmentPicker(),
);
