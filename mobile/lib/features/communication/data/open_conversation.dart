import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The conversation on screen. A push about a new message in it refreshes the
/// conversation instead of showing a banner.
class OpenConversation {
  final _arrived = StreamController<String>.broadcast();

  /// The id of the conversation on screen, or `null`.
  String? id;

  /// Ids of conversations that have a new message, as pushes report them.
  Stream<String> get arrived => _arrived.stream;

  /// Reports a new message in the conversation [conversationId].
  void messageArrived(String conversationId) => _arrived.add(conversationId);

  void _dispose() => _arrived.close();
}

final openConversationProvider = Provider<OpenConversation>((ref) {
  final open = OpenConversation();
  ref.onDispose(open._dispose);
  return open;
});
