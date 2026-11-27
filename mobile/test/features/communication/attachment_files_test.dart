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
  final paths = <String>[];

  setUp(() async {
    requests = 0;
    paths.clear();
    cache = await Directory.systemTemp.createTemp('attachments-test');
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) {
      requests++;
      paths.add(request.uri.path);
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

  test(
    'a photo is shown by its thumbnail, kept apart from the photo',
    () async {
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
        hasThumbnail: true,
      );
      expect(await files.cachedPreview(attachment), isNull);

      final preview = await files.preview('c', 'm', attachment);

      expect(preview.path, endsWith('/attachments/thumbnails/photo-1.jpg'));
      expect(paths, [
        '/api/v1/me/conversations/c/messages/m/attachments/photo-1/thumbnail',
      ]);
      expect((await files.cachedPreview(attachment))?.path, preview.path);
      await files.preview('c', 'm', attachment);
      expect(paths, hasLength(1));

      // Without a thumbnail, the whole photo is shown.
      const whole = ChatAttachment(
        id: 'photo-2',
        fileName: 'IMG.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1169457,
        isImage: true,
      );
      expect(
        (await files.preview('c', 'm', whole)).path,
        endsWith('/attachments/photo-2/IMG.jpg'),
      );
      expect(await files.cachedPreview(whole), isNull);
    },
  );

  test('keeps to its limit, the files used longest ago going first', () async {
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:${server.port}'));
    final files = AttachmentFiles(
      ConversationsApi(dio),
      directory: () async => cache,
      cacheLimit: 3 * 1024 * 1024,
    );
    final now = DateTime.now();
    Future<File> inCache(String path, int size, Duration unused) async {
      final file = File('${cache.path}/attachments/$path');
      await file.create(recursive: true);
      await file.writeAsBytes(Uint8List(size));
      await file.setLastModified(now.subtract(unused));
      return file;
    }

    final plan = await inCache(
      'old-1/plan.pdf',
      1024 * 1024,
      Duration(days: 3),
    );
    final older = await inCache(
      'old-2/IMG.jpg',
      1024 * 1024,
      Duration(days: 2),
    );
    final thumbnail = await inCache(
      'thumbnails/old-3.jpg',
      100 * 1024,
      const Duration(hours: 1),
    );
    // Opened again now, the plan is no longer the one used longest ago.
    await files.file(
      'c',
      'm',
      const ChatAttachment(
        id: 'old-1',
        fileName: 'plan.pdf',
        contentType: 'application/pdf',
        sizeBytes: 1024 * 1024,
        isImage: false,
      ),
    );
    expect(requests, 0);

    // 2.1 MB and the new 1.1 MB are over 3 MB: down to 2.5 MB.
    final downloaded = await files.file(
      'c',
      'm',
      const ChatAttachment(
        id: 'photo-1',
        fileName: 'IMG.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1169457,
        isImage: true,
      ),
    );

    expect(await older.exists(), isFalse);
    expect(await older.parent.exists(), isFalse);
    expect(await plan.exists(), isTrue);
    expect(await thumbnail.exists(), isTrue);
    expect(await downloaded.exists(), isTrue);
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
