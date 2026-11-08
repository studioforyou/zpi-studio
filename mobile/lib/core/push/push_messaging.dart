import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A push message: its visible text and the backend's `data`
/// (`notificationId`, `type` and e.g. `eventId`, `profileId`).
class PushMessage {
  const PushMessage({this.title, this.body, this.data = const {}});

  final String? title;
  final String? body;
  final Map<String, String> data;
}

/// The device's push channel (Firebase Cloud Messaging). Hidden behind an
/// interface so that tests and builds without Firebase use [NoPushMessaging].
abstract interface class PushMessaging {
  /// Asks the user to allow notifications; `true` when they are allowed.
  Future<bool> requestPermission();

  /// This installation's FCM token, or `null` when the device cannot receive
  /// pushes (e.g. iOS without an APNs key in Firebase).
  Future<String?> token();

  Stream<String> get onTokenRefresh;

  /// Messages that arrive while the app is open; the system shows nothing.
  Stream<PushMessage> get onForegroundMessage;

  /// A tap on a notification while the app was in the background.
  Stream<PushMessage> get onOpened;

  /// The notification whose tap started the app, if any.
  Future<PushMessage?> initialMessage();
}

class FirebasePushMessaging implements PushMessaging {
  FirebasePushMessaging([FirebaseMessaging? messaging])
    : _messaging = messaging ?? FirebaseMessaging.instance;

  final FirebaseMessaging _messaging;

  @override
  Future<bool> requestPermission() async {
    final settings = await _messaging.requestPermission();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  @override
  Future<String?> token() async {
    // Without an APNs token (no paid Apple Developer account yet, or the
    // simulator before it registers) FCM has no token to give on iOS.
    if (defaultTargetPlatform == TargetPlatform.iOS &&
        await _messaging.getAPNSToken() == null) {
      return null;
    }
    // The registration token the backend sends to (`Message.token` of the
    // HTTP v1 API). firebase_messaging 17 deprecates it in favour of
    // register()/onRegistered, which give a Firebase Installation ID for
    // FCM's newer direct-send instead; switch together with the backend.
    // ignore: deprecated_member_use
    return _messaging.getToken();
  }

  @override
  // ignore: deprecated_member_use
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Stream<PushMessage> get onForegroundMessage =>
      FirebaseMessaging.onMessage.map(_message);

  @override
  Stream<PushMessage> get onOpened =>
      FirebaseMessaging.onMessageOpenedApp.map(_message);

  @override
  Future<PushMessage?> initialMessage() async {
    final message = await _messaging.getInitialMessage();
    return message == null ? null : _message(message);
  }

  static PushMessage _message(RemoteMessage message) => PushMessage(
    title: message.notification?.title,
    body: message.notification?.body,
    data: {
      for (final entry in message.data.entries)
        entry.key: entry.value.toString(),
    },
  );
}

/// No push channel: nothing is registered and no message arrives.
class NoPushMessaging implements PushMessaging {
  const NoPushMessaging();

  @override
  Future<bool> requestPermission() async => false;

  @override
  Future<String?> token() async => null;

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Stream<PushMessage> get onForegroundMessage => const Stream.empty();

  @override
  Stream<PushMessage> get onOpened => const Stream.empty();

  @override
  Future<PushMessage?> initialMessage() async => null;
}

/// FCM once Firebase is initialized (the app's bootstrap); otherwise, e.g. in
/// widget tests, no push channel.
final pushMessagingProvider = Provider<PushMessaging>((ref) {
  try {
    if (Firebase.apps.isNotEmpty) return FirebasePushMessaging();
  } catch (_) {
    // Firebase is not available on this platform or in this test.
  }
  return const NoPushMessaging();
});
