import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

typedef ProfileViewKey = ({String accountId, String profileId});

final profileViewStorageProvider = Provider<FlutterSecureStorage>(
  (ref) => const FlutterSecureStorage(),
);

/// A display preference on this device, scoped to both account and participant.
/// It never changes guardianship or access to another profile's data.
class ProfileViewPreferences extends AsyncNotifier<bool> {
  ProfileViewPreferences(this.key);
  final ProfileViewKey key;
  String get _storageKey =>
      'show_related_profiles.${key.accountId}.${key.profileId}';

  @override
  Future<bool> build() async {
    try {
      return await ref
              .watch(profileViewStorageProvider)
              .read(key: _storageKey) !=
          'false';
    } catch (_) {
      // Keep the view limited to the current person if a preference cannot be read.
      return false;
    }
  }

  Future<void> setShowRelated(bool enabled) async {
    if (state.isLoading) return;
    final previous = state;
    final storage = ref.read(profileViewStorageProvider);
    state = const AsyncLoading();
    try {
      await storage.write(key: _storageKey, value: enabled.toString());
      state = AsyncData(enabled);
    } catch (_) {
      state = previous;
      rethrow;
    }
  }
}

final profileViewPreferencesProvider =
    AsyncNotifierProvider.family<ProfileViewPreferences, bool, ProfileViewKey>(
      ProfileViewPreferences.new,
    );
