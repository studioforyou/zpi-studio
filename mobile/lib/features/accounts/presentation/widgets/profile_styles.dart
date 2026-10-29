import 'package:flutter/material.dart';
import 'package:mobile/app/theme/brand_colors.dart';

// Every adjustable value of the profile UI, in one place: the tile itself
// (shared by both screens), the "Wybierz profil" screen and the header at the
// top of the profile tab. Sizes are Flutter logical pixels (the same physical
// size on every phone) or fractions of a tile's side where noted.

/// The tile itself, on both screens.
abstract final class ChoiceTileStyle {
  /// Corner radius as a fraction of the side.
  static const cornerRadius = 0.0;

  /// The letter in the square: height as a fraction of the side, and weight.
  static const letterSize = 0.5;
  static const letterWeight = FontWeight.w600;

  /// Text size of the name and of the role as a fraction of the side.
  static const nameSize = 0.14;
  static const roleSize = 0.11;
  static const nameWeight = FontWeight.w500;

  /// A name too long for the tile's width gets smaller, down to this
  /// fraction of its normal size; only then it is cut with "…".
  static const nameMinScale = 0.7;

  /// Gap between the square and the name, as a fraction of the side.
  static const labelGap = 0.06;

  /// Colours of client tiles, used in turn in the order of the account's
  /// profiles; the trainer tile always gets [trainerColor].
  static const clientColors = [
    BrandColors.teal300,
    BrandColors.teal100,
    BrandColors.teal500,
  ];
  static const trainerColor = BrandColors.teal700;

  /// Letter colours: dark on light squares, white on dark ones.
  static const darkLetterColor = Color.fromARGB(255, 20, 60, 71);
  static const lightLetterColor = Colors.white;

  /// Colours of the name and of the role below the square.
  static const nameColor = BrandColors.teal900;
  static const roleColor = BrandColors.teal500;
}

/// The "Wybierz profil" screen.
abstract final class RolePickerStyle {
  /// Tiles in one row. The tile size follows from the row width divided by
  /// this number; extra tiles go to new rows and the screen scrolls.
  static const tilesPerRow = 2;

  /// The row is never wider than this, so tiles keep the same size on every
  /// phone.
  static const maxRowWidth = 280.0;

  /// Gap between tiles, horizontally and vertically.
  static const tileSpacing = 24.0;

  /// Background of the screen; `null` is the same as the rest of the app
  /// (from the theme).
  static const Color? backgroundColor = null;

  /// Space between the screen edges and the content, and below the last
  /// row of tiles.
  static const sideMargin = 24.0;
  static const bottomMargin = 24.0;

  /// The title is pinned to the top of the screen: its colour and weight,
  /// the space above it (below the status bar) and between it and the tiles.
  static const titleColor = BrandColors.teal900;
  static const titleWeight = FontWeight.w600;
  static const titleTopMargin = 24.0;
  static const titleBottomMargin = 28.0;

  /// "Powrót do logowania" / "Powrót do profilu" is pinned to the bottom of
  /// the screen, this far above its edge; it looks like the actions in the
  /// profile header ([ProfileHeaderStyle]).
  static const backBottomMargin = 24.0;
}

/// The header at the top of the profile tab: the active profile's tile and,
/// to its right, the full name, "Zmień profil" and "Wyloguj się".
abstract final class ProfileHeaderStyle {
  /// Side of the tile's square.
  static const tileSize = 100.0;

  /// Multiplies the size of the name and the role under the square.
  static const labelScale = 1.6;

  /// Line height of the name and the role under the square as a multiple of
  /// their text size. Smaller brings the role closer to the name (the font's
  /// own is about 1.17).
  static const labelLineHeight = 1.2;

  /// Gap between the square and the name under it, as a fraction of its
  /// side.
  static const labelGap = 0.08;

  /// Space above the tile, below the app bar.
  static const topMargin = 10.0;

  /// Space between the screen edges and the header.
  static const leftMargin = 25.0;
  static const rightMargin = 25.0;

  /// Gap between the tile and the text to its right.
  static const textGap = 20.0;

  /// The text to the right of the tile, all in capitals: weight and letter
  /// spacing. The block is centred vertically on the square.
  static const textWeight = FontWeight.w400;
  static const textLetterSpacing = 0.8;

  /// The full name: size and colour. When too long for one line it gets
  /// smaller, down to [nameMinSize]; still too long, it wraps onto up to
  /// [nameMaxLines] lines, and only then is cut with "…".
  static const nameSize = 21.0;
  static const nameMinSize = 15.0;
  static const nameMaxLines = 2;
  static const nameColor = Colors.black;

  /// "Zmień profil" and "Wyloguj się": size and colour.
  static const actionSize = 18.0;
  static const actionColor = BrandColors.teal500;

  /// Gap between the full name and "Zmień profil", and between the two
  /// actions.
  static const nameActionsGap = 16.0;
  static const actionsGap = 8.0;

  /// The line under the header: thickness, colour, the space above and below
  /// it, and the space between its ends and the screen edges.
  static const dividerThickness = 1.0;
  static const dividerColor = Color.fromARGB(255, 215, 215, 215);
  static const dividerMargin = 16.0;
  static const dividerSideMargin = 20.0;
}
