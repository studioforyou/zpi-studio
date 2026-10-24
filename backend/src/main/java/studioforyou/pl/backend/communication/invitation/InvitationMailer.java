package studioforyou.pl.backend.communication.invitation;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import studioforyou.pl.backend.communication.mail.EmailMessage;
import studioforyou.pl.backend.communication.mail.EmailSender;
import studioforyou.pl.backend.communication.mail.EmailSendingException;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.identity.AccountInvited;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Sends the invitation e-mail when an account is invited. Runs in the inviting transaction, so a
 * failed send rolls the invitation back and the caller gets 502 {@code EMAIL_NOT_SENT}.
 */
@Component
class InvitationMailer {

	private static final Logger log = LoggerFactory.getLogger(InvitationMailer.class);

	private static final String APP_NAME = "studioforyou";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private final EmailSender emailSender;

	private final StudioRepository studioRepository;

	InvitationMailer(EmailSender emailSender, StudioRepository studioRepository) {
		this.emailSender = emailSender;
		this.studioRepository = studioRepository;
	}

	@EventListener
	void onAccountInvited(AccountInvited event) {
		Studio studio = studioRepository.findFirstBy().orElse(null);
		String studioName = studio != null ? studio.getName() : APP_NAME;
		ZoneId zone = ZoneId.of(studio != null ? studio.getTimeZone() : "Europe/Warsaw");
		String validUntil = event.expiresAt().atZoneSameInstant(zone).format(DATE);
		boolean trainer = "TRAINER".equals(event.role());
		String as = trainer ? "trenera" : "klienta";
		String what = trainer ? "zobaczysz swój grafik i listę uczestników zajęć"
				: "zapiszesz się na zajęcia i sprawdzisz swoje karnety";

		String text = """
				Cześć %s,

				studio %s zaprasza Cię do aplikacji %s.

				Aktywuj konto, ustawiając hasło pod tym adresem (link jest ważny do %s):
				%s

				Jeśli nie spodziewasz się tego zaproszenia, zignoruj tę wiadomość.
				""".formatted(event.firstName(), studioName, APP_NAME, validUntil, event.link());
		String html = """
				<!DOCTYPE html>
				<html lang="pl"><body style="margin:0;padding:24px;background:#f4f7f8;font-family:Arial,Helvetica,sans-serif;color:#1f2d33">
				<div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px">
				<p style="font-size:13px;letter-spacing:2px;color:#0e7c86;margin:0 0 24px">%s</p>
				<p style="font-size:16px;margin:0 0 16px">Cześć %s,</p>
				<p style="font-size:16px;line-height:1.5;margin:0 0 24px">studio <strong>%s</strong> zaprasza Cię do aplikacji %s.</p>
				<p style="text-align:center;margin:50px 0 25px"><a href="%s" style="display:inline-block;padding:14px 28px;border-radius:8px;background:linear-gradient(90deg,#0e7c86,#2bb3a3);background-color:#0e7c86;color:#ffffff;text-decoration:none;font-weight:bold">Aktywuj konto</a></p>
				<p style="font-size:11px;line-height:1.5;color:#5b6b70;margin:50px 0 8px">Link jest ważny do %s. Jeśli przycisk nie działa, skopiuj adres:<br><a href="%s" style="color:#0e7c86;word-break:break-all">%s</a></p>
				<p style="font-size:11px;color:#5b6b70;margin:10px 0 0">Jeśli nie spodziewasz się tego zaproszenia, zignoruj tę wiadomość.</p>
				</div></body></html>
				""".formatted(APP_NAME.toUpperCase(), esc(event.firstName()), esc(studioName), APP_NAME, esc(event.link()),
				validUntil, esc(event.link()), esc(event.link()));

		try {
			emailSender.send(new EmailMessage(event.email(), event.firstName(), "Zaproszenie do " + APP_NAME, html,
					text));
		}
		catch (EmailSendingException ex) {
			log.warn("Invitation e-mail to {} not sent", event.email(), ex);
			throw new ApiException(ErrorCode.EMAIL_NOT_SENT, "The invitation e-mail could not be sent; try again later.");
		}
	}

	private static String esc(String value) {
		return HtmlUtils.htmlEscape(value);
	}

}
