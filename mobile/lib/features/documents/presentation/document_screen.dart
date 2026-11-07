import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/documents/data/documents_api.dart';
import 'package:mobile/features/documents/presentation/document_widgets.dart';
import 'package:mobile/features/documents/presentation/documents_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// One document of the active customer profile: its text in force and the
/// acceptance, or the consent and its withdrawal.
class DocumentScreen extends ConsumerWidget {
  const DocumentScreen({required this.documentId, super.key});

  final String documentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;
    if (session == null || profileId == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(child: Text(l10n.documentsNoProfile)),
      );
    }
    final profile = session.profiles
        .where((p) => p.id == profileId)
        .firstOrNull;
    final key = (profileId: profileId, documentId: documentId);
    final document = ref.watch(ownDocumentProvider(key));

    return Scaffold(
      appBar: AppBar(),
      body: document.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            spacing: 16,
            children: [
              Text(l10n.documentsLoadError),
              OutlinedButton(
                onPressed: () => ref.invalidate(ownDocumentProvider(key)),
                child: Text(l10n.documentsRetryButton),
              ),
            ],
          ),
        ),
        data: (document) => _DocumentBody(
          document: document,
          profileId: profileId,
          childName: profile != null && profile.isChild
              ? profile.displayName
              : null,
        ),
      ),
    );
  }
}

class _DocumentBody extends ConsumerStatefulWidget {
  const _DocumentBody({
    required this.document,
    required this.profileId,
    required this.childName,
  });

  final OwnDocument document;
  final String profileId;

  /// The child the guardian accepts for, or `null` for the account's own
  /// profile.
  final String? childName;

  @override
  ConsumerState<_DocumentBody> createState() => _DocumentBodyState();
}

class _DocumentBodyState extends ConsumerState<_DocumentBody> {
  bool _confirmed = false;
  bool _saving = false;

  Future<void> _run(
    Future<OwnDocument> Function(DocumentsApi api) action,
    String success,
  ) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _saving = true);
    try {
      await action(ref.read(documentsApiProvider));
      messenger.showSnackBar(SnackBar(content: Text(success)));
    } on DocumentApiException catch (e) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            e.isVersionConflict
                ? l10n.documentVersionConflict
                : l10n.documentActionError,
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() {
          _saving = false;
          _confirmed = false;
        });
      }
      ref
        ..invalidate(
          ownDocumentProvider((
            profileId: widget.profileId,
            documentId: widget.document.id,
          )),
        )
        ..invalidate(ownDocumentsProvider(widget.profileId));
    }
  }

  Future<void> _accept() {
    final l10n = AppLocalizations.of(context);
    final document = widget.document;
    return _run(
      (api) => api.accept(widget.profileId, document.id, document.versionId),
      document.required ? l10n.documentAccepted : l10n.documentConsentSaved,
    );
  }

  Future<void> _revoke() async {
    final l10n = AppLocalizations.of(context);
    if (!await confirmRevoke(context)) {
      return;
    }
    await _run(
      (api) => api.revoke(widget.profileId, widget.document.id),
      l10n.documentConsentRevoked,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    final document = widget.document;
    final publishedAt = document.publishedAt;
    final content = document.content;
    final childName = widget.childName;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(document.title, style: textTheme.headlineSmall),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  if (publishedAt != null)
                    Chip(
                      label: Text(
                        l10n.documentVersion(
                          document.versionNo,
                          formatDocumentDate(publishedAt),
                        ),
                      ),
                    ),
                  Chip(
                    avatar: document.required
                        ? DocumentStatusIcon(document)
                        : null,
                    label: Text(documentStatusText(l10n, document)),
                  ),
                ],
              ),
              if (document.requiredFor.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    l10n.documentRequiredFor(document.requiredFor.join(', ')),
                  ),
                ),
              if (childName != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    l10n.documentForProfile(childName),
                    style: textTheme.titleSmall,
                  ),
                ),
              const SizedBox(height: 16),
              Card.outlined(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: content != null
                      ? SelectableText(content, style: textTheme.bodyLarge)
                      : Row(
                          spacing: 12,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Icon(Icons.picture_as_pdf_outlined),
                            Expanded(child: Text(l10n.documentFileOnly)),
                          ],
                        ),
                ),
              ),
            ],
          ),
        ),
        _ActionBar(
          document: document,
          confirmed: _confirmed,
          saving: _saving,
          onConfirmedChanged: (value) => setState(() => _confirmed = value),
          onAccept: _accept,
          onRevoke: _revoke,
        ),
      ],
    );
  }
}

/// The bottom of the screen: confirm and accept, or withdraw a consent.
class _ActionBar extends StatelessWidget {
  const _ActionBar({
    required this.document,
    required this.confirmed,
    required this.saving,
    required this.onConfirmedChanged,
    required this.onAccept,
    required this.onRevoke,
  });

  final OwnDocument document;
  final bool confirmed;
  final bool saving;
  final ValueChanged<bool> onConfirmedChanged;
  final VoidCallback onAccept;
  final VoidCallback onRevoke;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final colors = Theme.of(context).colorScheme;
    final Widget content;
    if (!document.isAccepted) {
      content = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CheckboxListTile(
            value: confirmed,
            onChanged: saving
                ? null
                : (value) => onConfirmedChanged(value ?? false),
            title: Text(
              document.required
                  ? l10n.documentConfirmAccept
                  : l10n.documentConfirmConsent,
            ),
            controlAffinity: ListTileControlAffinity.leading,
            contentPadding: EdgeInsets.zero,
          ),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: confirmed && !saving ? onAccept : null,
              child: saving
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(
                      document.required
                          ? l10n.documentAcceptButton
                          : l10n.documentConsentButton,
                    ),
            ),
          ),
        ],
      );
    } else if (document.revocable) {
      content = SizedBox(
        width: double.infinity,
        child: OutlinedButton(
          onPressed: saving ? null : onRevoke,
          child: Text(l10n.documentRevokeButton),
        ),
      );
    } else {
      content = Text(
        l10n.documentRequiredInfo,
        style: Theme.of(context).textTheme.bodySmall,
      );
    }
    return Material(
      color: colors.surfaceContainer,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: content,
        ),
      ),
    );
  }
}
