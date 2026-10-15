package studioforyou.pl.backend.configuration;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "studio")
public class Studio {

	@Id
	private UUID id;

	private String name;

	@Column(name = "time_zone")
	private String timeZone;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	protected Studio() {
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getTimeZone() {
		return this.timeZone;
	}

	public String getCurrency() {
		return this.currency;
	}

}
