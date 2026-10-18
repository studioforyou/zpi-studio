import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/firebase_options_stage.dart' as firebase_stage;

Future<void> bootstrap(AppConfig config) async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(options: _firebaseOptions(config.flavor));
  runApp(
    ProviderScope(
      overrides: [appConfigProvider.overrideWithValue(config)],
      child: const App(),
    ),
  );
}

/// Each environment has its own Firebase project, generated with
/// `flutterfire configure --out=lib/firebase_options_<env>.dart`.
FirebaseOptions _firebaseOptions(AppFlavor flavor) => switch (flavor) {
  // TODO(auth): use the dev project's options once the dev Firebase project exists.
  AppFlavor.dev => firebase_stage.DefaultFirebaseOptions.currentPlatform,
  AppFlavor.staging => firebase_stage.DefaultFirebaseOptions.currentPlatform,
};
