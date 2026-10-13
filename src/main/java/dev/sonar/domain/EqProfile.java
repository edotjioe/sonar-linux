package dev.sonar.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A named EQ: up to {@link EqBand#MAX_BANDS} bands. Built-in presets can't be changed (they're rewritten on startup). */
@Entity
public class EqProfile {

	public static final Map<String, List<EqBand>> PRESETS = new LinkedHashMap<>();

	static {
		PRESETS.put("Flat", List.of());
		PRESETS.put("Bass Boost", List.of(new EqBand("lowshelf", 120, 6, 0.71)));
		PRESETS.put("Footsteps", List.of(new EqBand("lowshelf", 200, -4, 0.71), new EqBand("peaking", 3000, 5, 0.9)));
		PRESETS.put("Voice Clarity", List.of(new EqBand("highpass", 90, 0, 0.71), new EqBand("peaking", 300, -3, 1),
				new EqBand("peaking", 3000, 4, 0.8)));
		PRESETS.put("Treble Boost", List.of(new EqBand("highshelf", 4000, 6, 0.71)));
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true)
	private String name;

	/** Before parametric bands: one gain per graphic band. Only read when bands is null. */
	private double[] gains;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<EqBand> bands;

	/** Bass, Voice, Treble (dB), see {@link EqBand#tone}; null (older rows, built-ins) = all 0. */
	private double[] tone;

	private boolean builtIn;

	public EqProfile() {
	}

	public EqProfile(String name, List<EqBand> bands, boolean builtIn) {
		this.name = name;
		this.bands = bands;
		this.builtIn = builtIn;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public List<EqBand> getBands() {
		return bands != null ? bands : EqBand.fromLegacy(gains);
	}

	public void setBands(List<EqBand> bands) {
		this.bands = bands;
	}

	public double[] getTone() {
		return tone == null ? new double[3] : tone;
	}

	public void setTone(double[] tone) {
		this.tone = tone;
	}

	public boolean isBuiltIn() {
		return builtIn;
	}
}
