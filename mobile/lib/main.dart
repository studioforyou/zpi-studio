import 'package:mobile/app/bootstrap.dart';
import 'package:mobile/app/config/app_config.dart';

/// Single entry point; the environment comes from `env/<flavor>.json`
/// passed with `--dart-define-from-file`.
Future<void> main() async {
  const flavor = String.fromEnvironment('APP_FLAVOR', defaultValue: 'dev');
  await bootstrap(flavor == 'staging' ? AppConfig.staging() : AppConfig.dev());
}
