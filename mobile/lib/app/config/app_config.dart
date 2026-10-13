import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

enum AppFlavor { dev, staging }

class AppConfig {
  const AppConfig({required this.flavor, required this.apiBaseUrl});

  /// Running locally against a backend on this machine. `API_BASE_URL` is
  /// optional; without it the default local backend address for the current
  /// platform is used.
  factory AppConfig.dev() {
    const fromEnv = String.fromEnvironment('API_BASE_URL');
    if (fromEnv.isNotEmpty) {
      return const AppConfig(flavor: AppFlavor.dev, apiBaseUrl: fromEnv);
    }
    return AppConfig(
      flavor: AppFlavor.dev,
      apiBaseUrl: defaultTargetPlatform == TargetPlatform.android
          ? 'http://10.0.2.2:8080'
          : 'http://localhost:8080',
    );
  }

  /// Staging. `API_BASE_URL` must be passed with `--dart-define`.
  factory AppConfig.staging() {
    const fromEnv = String.fromEnvironment('API_BASE_URL');
    if (fromEnv.isEmpty) {
      throw StateError('API_BASE_URL must be provided for staging');
    }
    return const AppConfig(flavor: AppFlavor.staging, apiBaseUrl: fromEnv);
  }

  final AppFlavor flavor;
  final String apiBaseUrl;
}

/// Always overridden in `bootstrap`.
final appConfigProvider = Provider<AppConfig>(
  (ref) => throw UnimplementedError(),
);
