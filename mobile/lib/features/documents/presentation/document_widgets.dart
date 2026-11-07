import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/documents/data/documents_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// `2026-11-01` → `01.11.2026`.
String formatDocumentDate(DateTime date) =>
    DateFormat('dd.MM.yyyy').format(date);

/// An icon for the kind of a document.
IconData documentIcon(DocumentKind kind) => switch (kind) {
  DocumentKind.terms => Icons.gavel_outlined,
  DocumentKind.gdpr => Icons.privacy_tip_outlined,
  DocumentKind.participationConsent => Icons.health_and_safety_outlined,
  DocumentKind.parentalConsent => Icons.family_restroom,
  DocumentKind.marketingEmail => Icons.mail_outline,
  DocumentKind.marketingSms => Icons.sms_outlined,
  DocumentKind.other => Icons.description_outlined,
};

/// One line saying whether the document is accepted, and since when.
String documentStatusText(AppLocalizations l10n, OwnDocument document) {
  final acceptedAt = document.acceptedAt;
  if (!document.required) {
    return document.isAccepted && acceptedAt != null
        ? l10n.documentConsentGiven(formatDocumentDate(acceptedAt))
        : l10n.documentConsentNotGiven;
  }
  return switch (document.status) {
    AcceptanceStatus.accepted when acceptedAt != null =>
      l10n.documentStatusAccepted(formatDocumentDate(acceptedAt)),
    AcceptanceStatus.reacceptanceRequired => l10n.documentStatusReaccept,
    _ => l10n.documentStatusNotAccepted,
  };
}

/// A small icon showing the acceptance status of a required document.
class DocumentStatusIcon extends StatelessWidget {
  const DocumentStatusIcon(this.document, {super.key});

  final OwnDocument document;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return document.isAccepted
        ? Icon(Icons.check_circle, color: colors.primary)
        : Icon(Icons.error_outline, color: colors.error);
  }
}
