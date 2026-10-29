import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Remembers, per account, which profile the app was last used as (a
/// [SessionChoice.key]), so a restarted app opens on it again.
abstract interface class ChoiceStore {
  Future<String?> read(String accountId);

  Future<void> write(String accountId, String choiceKey);
}

class SecureChoiceStore implements ChoiceStore {
  const SecureChoiceStore(this._storage);

  final FlutterSecureStorage _storage;

  static String _key(String accountId) => 'last_choice.$accountId';

  @override
  Future<String?> read(String accountId) => _storage.read(key: _key(accountId));

  @override
  Future<void> write(String accountId, String choiceKey) =>
      _storage.write(key: _key(accountId), value: choiceKey);
}

/// Keeps the choices in memory only; used by tests.
class InMemoryChoiceStore implements ChoiceStore {
  final values = <String, String>{};

  @override
  Future<String?> read(String accountId) async => values[accountId];

  @override
  Future<void> write(String accountId, String choiceKey) async =>
      values[accountId] = choiceKey;
}

final choiceStoreProvider = Provider<ChoiceStore>(
  (ref) => const SecureChoiceStore(FlutterSecureStorage()),
);
