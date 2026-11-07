import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/push/devices_api.dart';
import 'package:mobile/core/push/push_messaging.dart';

/// This device as a receiver of the signed-in account's push notifications:
/// registers its FCM token with the backend after sign-in (and whenever FCM
/// replaces it) and removes it before sign-out.
class PushDevice {
  PushDevice(this._ref);

  final Ref _ref;
  String? _token;
  bool _signedIn = false;
  StreamSubscription<String>? _refresh;

  PushMessaging get _messaging => _ref.read(pushMessagingProvider);

  /// Registers this device for the account that has just signed in. Pushes
  /// are optional: a refused permission or a missing token only means the
  /// notifications stay in the in-app inbox.
  Future<void> register() async {
    _signedIn = true;
    _refresh ??= _messaging.onTokenRefresh.listen(
      (token) => unawaited(_send(token)),
    );
    try {
      if (!await _messaging.requestPermission()) return;
      final token = await _messaging.token();
      if (token != null && _signedIn) await _send(token);
    } catch (error) {
      debugPrint('Push registration skipped: $error');
    }
  }

  /// Stops pushes to this device. Called while the session is still valid,
  /// so the backend knows whose token it removes; never blocks sign-out.
  Future<void> unregister() async {
    _signedIn = false;
    final token = _token;
    _token = null;
    if (token == null) return;
    try {
      await _ref
          .read(devicesApiProvider)
          .unregister(token)
          .timeout(const Duration(seconds: 5));
    } catch (error) {
      debugPrint('Push unregistration failed: $error');
    }
  }

  Future<void> _send(String token) async {
    if (!_signedIn) return;
    try {
      await _ref.read(devicesApiProvider).register(token, platform: _platform);
      _token = token;
    } catch (error) {
      debugPrint('Push registration failed: $error');
    }
  }

  static String get _platform =>
      defaultTargetPlatform == TargetPlatform.iOS ? 'IOS' : 'ANDROID';

  void dispose() => _refresh?.cancel();
}

final pushDeviceProvider = Provider<PushDevice>((ref) {
  final device = PushDevice(ref);
  ref.onDispose(device.dispose);
  return device;
});
