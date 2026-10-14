import 'package:flutter/material.dart';

ThemeData buildAppTheme() {
  return ThemeData(
    useMaterial3: true,
    // TODO(#5): replace with design tokens from Figma
    colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
  );
}
