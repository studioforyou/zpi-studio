package studioforyou.pl.backend.communication.announcement;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.AnnouncementTemplate;
import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.CreateTemplateRequest;
import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.UpdateTemplateRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Announcement templates the administrator defines once and reuses when writing an announcement.
 * A sent announcement copies the content, so changing or removing a template never changes what
 * was already sent.
 */
@Service
class AnnouncementTemplateService {

	private final AnnouncementTemplateStore store;

	AnnouncementTemplateService(AnnouncementTemplateStore store) {
		this.store = store;
	}

	@Transactional(readOnly = true)
	List<AnnouncementTemplate> list() {
		return store.list();
	}

	@Transactional(readOnly = true)
	AnnouncementTemplate get(UUID id) {
		return store.find(id).orElseThrow(AnnouncementTemplateService::notFound);
	}

	@Transactional
	AnnouncementTemplate create(CreateTemplateRequest request, UUID actor) {
		String name = text("name", request.name());
		String title = text("title", request.title());
		String body = text("body", request.body());
		requireFreeName(name, null);
		UUID id;
		try {
			id = store.insert(name, title, body, Boolean.TRUE.equals(request.marketing()), actor);
		}
		catch (DuplicateKeyException ex) {
			throw nameAlreadyUsed();
		}
		return get(id);
	}

	@Transactional
	AnnouncementTemplate update(UUID id, UpdateTemplateRequest request, UUID actor) {
		AnnouncementTemplate current = get(id);
		String name = request.name() != null ? text("name", request.name()) : current.name();
		String title = request.title() != null ? text("title", request.title()) : current.title();
		String body = request.body() != null ? text("body", request.body()) : current.body();
		boolean marketing = request.marketing() != null ? request.marketing() : current.marketing();
		requireFreeName(name, id);
		try {
			store.update(id, name, title, body, marketing, actor);
		}
		catch (DuplicateKeyException ex) {
			throw nameAlreadyUsed();
		}
		return get(id);
	}

	@Transactional
	void delete(UUID id) {
		if (!store.delete(id)) {
			throw notFound();
		}
	}

	private void requireFreeName(String name, @Nullable UUID exceptId) {
		if (store.nameUsed(name, exceptId)) {
			throw nameAlreadyUsed();
		}
	}

	/** The value without surrounding whitespace; whitespace only counts as missing. */
	private static String text(String field, String value) {
		String stripped = value.strip();
		if (stripped.isEmpty()) {
			throw ApiException.invalidField(field, FieldErrorCode.REQUIRED, "Must not be blank.");
		}
		return stripped;
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Announcement template not found.");
	}

	private static ApiException nameAlreadyUsed() {
		return new ApiException(ErrorCode.ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED,
				"An announcement template with this name already exists.");
	}

}
