import 'package:flutter/material.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class PlaceholderScreen extends StatelessWidget {
  const PlaceholderScreen({super.key, required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Center(
        child: Text(AppLocalizations.of(context).placeholderComingSoon),
      ),
    );
  }
}
