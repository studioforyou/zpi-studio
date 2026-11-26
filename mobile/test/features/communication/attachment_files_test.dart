import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gal/gal.dart';
import 'package:gal/gal_platform_interface.dart';
import 'package:mobile/features/communication/data/attachment_files.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';

/// The phone's gallery: saves the photos, or fails as it is told to.
final class _FakeGallery extends GalPlatform {
  final saved = <String>[];
  GalExceptionType? failure;

  @override
  Future<void> putImage(String path, {String? album}) async {
    final type = failure;
    if (type != null) {
      throw GalException(
        type: type,
        platformException: PlatformException(code: type.code),
        stackTrace: StackTrace.current,
      );
    }
    saved.add(path);
  }
}

void main() {
  late HttpServer server;
  late Directory cache;
  final photo = Uint8List.fromList(List.generate(1169457, (i) => i % 251));
  var requests = 0;

  setUp(() async {
    requests = 0;
    cache = await Directory.systemTemp.createTemp('attachments-test');
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) {
      requests++;
      // As the backend answers: the content with its length and type.
      request.response
        ..headers.contentType = ContentType('image', 'jpeg')
        ..headers.contentLength = photo.length
        ..headers.set('Content-Disposition', 'inline; filename="IMG.jpg"')
        ..headers.set('Cache-Control', 'max-age=604800, private')
        ..add(photo);
      request.response.close();
    });
  });

  tearDown(() async {
    await server.close(force: true);
    await cache.delete(recursive: true);
  });

  test('downloads an attachment once and keeps it in the cache', () async {
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:${server.port}'));
    final files = AttachmentFiles(
      ConversationsApi(dio),
      directory: () async => cache,
    );
    const attachment = ChatAttachment(
      id: 'photo-1',
      fileName: 'IMG.jpg',
      contentType: 'image/jpeg',
      sizeBytes: 1169457,
      isImage: true,
    );

    final file = await files
        .file('c', 'm', attachment)
        .timeout(const Duration(seconds: 10));

    expect(await file.readAsBytes(), photo);
    expect(file.path, endsWith('/attachments/photo-1/IMG.jpg'));
    // Shown again, it comes from the cache.
    await files.file('c', 'm', attachment);
    expect(requests, 1);
  });

  test('saves a photo in the gallery and says why it could not', () async {
    final gallery = _FakeGallery();
    GalPlatform.instance = gallery;
    final files = AttachmentFiles(ConversationsApi(Dio()));
    final photo = File('${cache.path}/IMG.jpg');

    expect(await files.saveToGallery(photo), GallerySave.saved);
    expect(gallery.saved, [photo.path]);
    gallery.failure = GalExceptionType.permissionDenied;
    expect(await files.saveToGallery(photo), GallerySave.denied);
    gallery.failure = GalExceptionType.notSupportedFormat;
    expect(await files.saveToGallery(photo), GallerySave.unsupported);
    gallery.failure = GalExceptionType.notEnoughSpace;
    expect(await files.saveToGallery(photo), GallerySave.failed);
  });
}
