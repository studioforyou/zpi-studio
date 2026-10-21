/// Roles that have a mobile app. The API sends them as `CLIENT` and `TRAINER`;
/// other account roles (e.g. administrators) use the web panel and are
/// skipped by [AppRole.fromApi].
enum AppRole {
  client('CLIENT'),
  trainer('TRAINER');

  const AppRole(this.apiValue);

  /// The role code used by the API.
  final String apiValue;

  /// The role for an API role code, or `null` when it has no mobile app.
  static AppRole? fromApi(String value) {
    for (final role in values) {
      if (role.apiValue == value) return role;
    }
    return null;
  }
}
