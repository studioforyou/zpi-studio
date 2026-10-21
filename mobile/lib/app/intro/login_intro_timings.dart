import 'package:flutter/animation.dart';

/// Timings of the logo intro played after a successful login.
///
/// Every stage starts when the previous one ends, unless said otherwise, so
/// changing one duration shifts the later stages with it. All values are in
/// seconds; the curves say how a stage speeds up or slows down.
class LoginIntroTimings {
  const LoginIntroTimings({
    this.curtains = 0.4,
    this.curtainsCurve = Curves.easeInOutCubic,
    this.lettersDelay = 0.1,
    this.letters = 0.6,
    this.lettersCurve = Curves.easeOutQuart,
    this.pauseBeforePulse = 0.2,
    this.pulse = 0.35,
    this.pulseScale = 0.04,
    this.pauseBeforeReveal = 0.33,
    this.reveal = 0.48,
    this.revealCurve = Curves.easeInQuart,
  });

  /// The white curtains slide in from both sides and close over the login
  /// screen.
  final double curtains;
  final Curve curtainsCurve;

  /// How long after the curtains start the 4 and the U start flying in
  /// behind them. They are only drawn on the curtains, never over the login
  /// screen.
  final double lettersDelay;

  /// The 4 flies in from the left and the U from the right until they meet.
  final double letters;
  final Curve lettersCurve;

  final double pauseBeforePulse;

  /// The joined logo grows by [pulseScale] and shrinks back, while STUDIO
  /// fades in.
  final double pulse;
  final double pulseScale;

  final double pauseBeforeReveal;

  /// The white screen with the logo slides up and uncovers the app.
  final double reveal;
  final Curve revealCurve;

  double get lettersStart => lettersDelay;
  double get lettersEnd => lettersStart + letters;

  /// The pulse waits for both the curtains and the letters.
  double get pulseStart =>
      (lettersEnd > curtains ? lettersEnd : curtains) + pauseBeforePulse;
  double get pulseEnd => pulseStart + pulse;
  double get revealStart => pulseEnd + pauseBeforeReveal;
  double get total => revealStart + reveal;
}
