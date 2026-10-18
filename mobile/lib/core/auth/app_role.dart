/// A role the mobile app has a shell for. [code] is the backend role code.
enum AppRole {
  client('CLIENT'),
  trainer('TRAINER');

  const AppRole(this.code);

  final String code;

  /// The app role for a backend role code, or `null` for roles the mobile
  /// app does not support (e.g. `ADMIN`).
  static AppRole? fromCode(String code) {
    for (final role in values) {
      if (role.code == code) return role;
    }
    return null;
  }
}
