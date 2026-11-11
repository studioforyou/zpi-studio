package studioforyou.pl.backend.reporting.internal;

import java.util.EnumSet;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kinds of events with participants that reports can cover. */
@Schema(name = "ReportEventKind")
enum ReportEventKind {

	/** A group class, usually part of a recurring series. */
	CLASS,

	/** A one-off workshop. */
	WORKSHOP,

	PERSONAL_TRAINING;

	/** Events whose places a group shares. */
	static final Set<ReportEventKind> GROUP = EnumSet.of(CLASS, WORKSHOP);

	static final Set<ReportEventKind> ALL = EnumSet.allOf(ReportEventKind.class);

}
