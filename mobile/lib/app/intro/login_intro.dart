import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/intro/login_intro_timings.dart';
import 'package:mobile/app/intro/logo_paths.dart';
import 'package:mobile/app/theme/brand_colors.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';

/// Whether the login screen may be left after a successful login.
///
/// The router keeps the login screen until the intro has covered it, so the
/// app never flashes up before the curtains close.
class LoginCoveredNotifier extends Notifier<bool> {
  @override
  bool build() => false;

  void cover() => state = true;

  void reset() => state = false;
}

final loginCoveredProvider = NotifierProvider<LoginCoveredNotifier, bool>(
  LoginCoveredNotifier.new,
);

/// A profile picked on the role picker opened from the profile tab. The
/// intro plays and the app switches to it once the curtains have closed.
class ProfileSwitchNotifier extends Notifier<SessionChoice?> {
  @override
  SessionChoice? build() => null;

  void request(SessionChoice choice) => state = choice;

  void clear() => state = null;
}

final profileSwitchProvider =
    NotifierProvider<ProfileSwitchNotifier, SessionChoice?>(
      ProfileSwitchNotifier.new,
    );

/// Plays the logo intro over the whole app after a successful login and when
/// the profile is changed from the profile tab: white curtains close over the
/// screen, the 4 and the U fly in and join, STUDIO appears, then the logo
/// slides up and uncovers the app.
///
/// Not played when a saved session is restored, skipped by a tap, and left
/// out when the system asks for reduced motion.
class LoginIntro extends ConsumerStatefulWidget {
  const LoginIntro({
    super.key,
    required this.child,
    this.timings = const LoginIntroTimings(),
  });

  final Widget child;
  final LoginIntroTimings timings;

  @override
  ConsumerState<LoginIntro> createState() => _LoginIntroState();
}

class _LoginIntroState extends ConsumerState<LoginIntro>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(
          vsync: this,
          duration: Duration(
            microseconds:
                (widget.timings.total * Duration.microsecondsPerSecond).round(),
          ),
        )
        ..addListener(_coverOnceClosed)
        ..addStatusListener(_finish);

  bool _playing = false;

  /// Runs once, when the curtains have closed (or right away without
  /// animations).
  VoidCallback? _onCovered;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _start(VoidCallback onCovered) {
    FocusManager.instance.primaryFocus?.unfocus();
    _onCovered = onCovered;
    if (MediaQuery.disableAnimationsOf(context)) {
      _cover();
      return;
    }
    setState(() => _playing = true);
    _controller.forward(from: 0);
  }

  void _coverLogin() {
    if (!ref.read(loginCoveredProvider)) {
      ref.read(loginCoveredProvider.notifier).cover();
    }
  }

  void _switchProfile(SessionChoice? choice) {
    if (choice == null) {
      return;
    }
    if (_playing) {
      ref.read(profileSwitchProvider.notifier).clear();
      return;
    }
    _start(() {
      ref.read(authControllerProvider.notifier).choose(choice);
      ref.read(profileSwitchProvider.notifier).clear();
    });
  }

  void _coverOnceClosed() {
    if (_controller.value * widget.timings.total >= widget.timings.curtains) {
      _cover();
    }
  }

  void _finish(AnimationStatus status) {
    if (status.isCompleted) {
      _cover();
      setState(() => _playing = false);
    }
  }

  void _cover() {
    final onCovered = _onCovered;
    _onCovered = null;
    onCovered?.call();
  }

  void _stop() {
    _onCovered = null;
    ref.read(loginCoveredProvider.notifier).reset();
    ref.read(profileSwitchProvider.notifier).clear();
    _controller.reset();
    if (_playing) setState(() => _playing = false);
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AuthState>(authControllerProvider, (previous, next) {
      if (next is Unauthenticated) {
        _stop();
      } else if (previous is Unauthenticated && next is Authenticated) {
        _start(_coverLogin);
      }
    });
    ref.listen<SessionChoice?>(
      profileSwitchProvider,
      (_, next) => _switchProfile(next),
    );

    return Stack(
      children: [
        widget.child,
        if (_playing)
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => _controller.value = 1,
              child: CustomPaint(
                painter: LoginIntroPainter(_controller, widget.timings),
              ),
            ),
          ),
      ],
    );
  }
}

class LoginIntroPainter extends CustomPainter {
  LoginIntroPainter(this.animation, this.timings) : super(repaint: animation);

  final Animation<double> animation;
  final LoginIntroTimings timings;

  /// Width of the logo as a fraction of the shorter screen side.
  static const _logoSize = 0.6;

  static final _four = logoFourPath();
  static final _u = logoUPath();
  static final _studio = logoStudioPath();

  // The J1 gradients, in logo units.
  static final _fourPaint = Paint()
    ..shader = ui.Gradient.linear(
      const Offset(150, 0),
      const Offset(900, 1000),
      const [BrandColors.teal300, BrandColors.teal500],
      const [0, 0.45],
    );
  static final _uPaint = Paint()
    ..shader = ui.Gradient.linear(
      const Offset(150, 0),
      const Offset(900, 1000),
      const [BrandColors.teal700, BrandColors.teal900],
      const [0.3, 1],
    );

  @override
  void paint(Canvas canvas, Size size) {
    final t = animation.value * timings.total;
    double progress(double start, double end, [Curve curve = Curves.linear]) {
      if (end <= start) return t >= end ? 1 : 0;
      return curve.transform(((t - start) / (end - start)).clamp(0, 1));
    }

    final closed = progress(0, timings.curtains, timings.curtainsCurve);
    final flown = progress(
      timings.lettersStart,
      timings.lettersEnd,
      timings.lettersCurve,
    );
    final pulse = progress(timings.pulseStart, timings.pulseEnd);
    final revealed = progress(
      timings.revealStart,
      timings.total,
      timings.revealCurve,
    );

    canvas.translate(0, -size.height * revealed);

    // One path for both curtains, so no seam shows where they meet.
    final half = size.width / 2 * closed;
    final curtains = Path()
      ..addRect(Rect.fromLTWH(0, 0, half, size.height))
      ..addRect(Rect.fromLTWH(size.width - half, 0, half, size.height));
    canvas.drawPath(curtains, Paint()..color = BrandColors.paper);

    // The letters only show on the curtains, never over the login screen.
    canvas.clipPath(curtains);
    final scale = size.shortestSide * _logoSize / 1000;
    // Far enough for each letter to start just past its screen edge.
    final travel = (300 + size.width / 2 / scale) * (1 - flown);
    canvas
      ..translate(size.width / 2, size.height / 2)
      ..scale(scale * (1 + timings.pulseScale * 4 * pulse * (1 - pulse)))
      ..translate(-500, -500)
      // Moving the canvas, not the paths, takes each gradient along.
      ..save()
      ..translate(travel, 0)
      ..drawPath(_u, _uPaint)
      ..translate(-2 * travel, 0)
      ..drawPath(_four, _fourPaint)
      ..restore()
      ..drawPath(
        _studio,
        Paint()
          ..color = BrandColors.paper.withValues(
            alpha: Curves.easeInOut.transform(pulse),
          ),
      );
  }

  @override
  bool shouldRepaint(LoginIntroPainter oldDelegate) =>
      oldDelegate.animation != animation || oldDelegate.timings != timings;
}
