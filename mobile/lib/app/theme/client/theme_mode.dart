import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the client app keeps its light / dark choice. It is stored per
/// device, not per account.
abstract interface class ThemeModeStore {
  Future<ThemeMode?> read();

  Future<void> write(ThemeMode mode);
}

class SecureThemeModeStore implements ThemeModeStore {
  const SecureThemeModeStore(this._storage);

  final FlutterSecureStorage _storage;

  static const _key = 'client_theme_mode';

  @override
  Future<ThemeMode?> read() async {
    final value = await _storage.read(key: _key);
    for (final mode in ThemeMode.values) {
      if (mode.name == value) return mode;
    }
    return null;
  }

  @override
  Future<void> write(ThemeMode mode) =>
      _storage.write(key: _key, value: mode.name);
}

/// Keeps the choice in memory only; used by tests.
class InMemoryThemeModeStore implements ThemeModeStore {
  InMemoryThemeModeStore([this.value]);

  ThemeMode? value;

  @override
  Future<ThemeMode?> read() async => value;

  @override
  Future<void> write(ThemeMode mode) async => value = mode;
}

final themeModeStoreProvider = Provider<ThemeModeStore>(
  (ref) => const SecureThemeModeStore(FlutterSecureStorage()),
);

/// The client app's theme mode. It follows the system until the person picks
/// light or dark in Profil.
class ThemeModeController extends Notifier<ThemeMode> {
  var _picked = false;

  @override
  ThemeMode build() {
    _restore();
    return ThemeMode.system;
  }

  Future<void> _restore() async {
    try {
      final stored = await ref.read(themeModeStoreProvider).read();
      if (stored != null && !_picked) state = stored;
    } on Exception {
      // Unreadable storage: keep following the system.
    }
  }

  Future<void> set(ThemeMode mode) async {
    _picked = true;
    state = mode;
    await ref.read(themeModeStoreProvider).write(mode);
  }
}

final themeModeProvider = NotifierProvider<ThemeModeController, ThemeMode>(
  ThemeModeController.new,
);
