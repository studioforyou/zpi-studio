import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/ping_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Development tool: checks the connection to the backend (`GET /api/ping`).
class PingScreen extends ConsumerStatefulWidget {
  const PingScreen({super.key});

  @override
  ConsumerState<PingScreen> createState() => _PingScreenState();
}

sealed class _PingResult {
  const _PingResult();
}

final class _Idle extends _PingResult {
  const _Idle();
}

final class _Loading extends _PingResult {
  const _Loading();
}

final class _Success extends _PingResult {
  const _Success(this.response);

  final String response;
}

final class _Failure extends _PingResult {
  const _Failure(this.error);

  final ApiException error;
}

class _PingScreenState extends ConsumerState<PingScreen> {
  _PingResult _result = const _Idle();

  Future<void> _ping() async {
    setState(() => _result = const _Loading());
    _PingResult result;
    try {
      result = _Success(await ref.read(pingApiProvider).ping());
    } on ApiException catch (e) {
      result = _Failure(e);
    }
    if (mounted) setState(() => _result = result);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final baseUrl = ref.watch(appConfigProvider).apiBaseUrl;
    final message = switch (_result) {
      _Idle() => l10n.pingIdle,
      _Loading() => l10n.pingConnecting,
      _Success(:final response) => l10n.pingSuccess(response),
      _Failure(error: ApiException(statusCode: final int statusCode)) =>
        l10n.pingServerError(statusCode),
      _Failure(:final error) => l10n.pingConnectionError(error.message),
    };

    return Scaffold(
      appBar: AppBar(title: Text(l10n.pingTitle)),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            spacing: 24,
            children: [
              Icon(
                Icons.cloud_sync,
                size: 60,
                color: Theme.of(context).colorScheme.primary,
              ),
              Text(
                message,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Text(baseUrl, style: Theme.of(context).textTheme.bodySmall),
              if (_result is _Loading) const CircularProgressIndicator(),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _result is _Loading ? null : _ping,
        tooltip: l10n.pingButton,
        child: const Icon(Icons.network_ping),
      ),
    );
  }
}
