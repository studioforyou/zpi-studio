import 'package:flutter/widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/placeholder_screen.dart';

class PassesScreen extends StatelessWidget {
  const PassesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return PlaceholderScreen(title: AppLocalizations.of(context).navPasses);
  }
}
