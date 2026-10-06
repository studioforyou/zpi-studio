# Mobile

Current state: a Flutter skeleton for Android and iOS. The [application entry point](lib/main.dart) displays a connection-check screen and calls the backend ping endpoint. Product screens and business workflows are not implemented.

## Find the relevant source

- [Application and ping screen](lib/main.dart)
- [Dependencies and SDK constraints](pubspec.yaml)
- [Widget test](test/widget_test.dart)
- [Android project](android/)
- [iOS project](ios/)

The backend URL is hardcoded in the app. Its current loopback address does not work for every device or emulator. The widget test still expects the original counter UI and does not match the ping screen.

The application skeleton is tracked in [#27](https://github.com/studioforyou/zpi-studio/issues/27), and UX work in [#33](https://github.com/studioforyou/zpi-studio/issues/33). See [local development and verification limitations](../docs/LOCAL_DEVELOPMENT.md) before treating setup or test commands as verified. Return to the [documentation map](../docs/README.md) for project context.
