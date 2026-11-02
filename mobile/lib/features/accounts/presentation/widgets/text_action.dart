import 'package:flutter/material.dart';
import 'package:mobile/features/accounts/presentation/widgets/profile_styles.dart';

/// A text-only action in capitals, like a link: "Zmień profil" and "Wyloguj
/// się" in the profile header, and the way back on the role picker. Its look
/// is set in [ProfileHeaderStyle].
class TextAction extends StatelessWidget {
  const TextAction({super.key, required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  static const _style = TextStyle(
    fontSize: ProfileHeaderStyle.actionSize,
    fontWeight: ProfileHeaderStyle.textWeight,
    letterSpacing: ProfileHeaderStyle.textLetterSpacing,
    color: ProfileHeaderStyle.actionColor,
  );

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Text(label.toUpperCase(), style: _style),
    );
  }
}
