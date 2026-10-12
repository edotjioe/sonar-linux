package dev.edo.sonar.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A named EQ curve: one gain (dB) per band in {@link #BANDS}. Built-in presets can't be changed. */
@Entity
public class EqProfile {

	/** Band centre frequencies (Hz): a one-octave graphic EQ. */
	public static final int[] BANDS = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };

	public static final double MAX_GAIN = 12;

	public static final Map<String, double[]> PRESETS = new LinkedHashMap<>();

	static {
		PRESETS.put("Flat", new double[BANDS.length]);
		PRESETS.put("Bass Boost", new double[] { 6, 5, 4, 2, 0, 0, 0, 0, 0, 0 });
		PRESETS.put("Footsteps", new double[] { -4, -3, -2, -1, 0, 2, 4, 5, 3, 0 });
		PRESETS.put("Voice Clarity", new double[] { -8, -6, -3, 0, 1, 3, 4, 3, 1, 0 });
		PRESETS.put("Treble Boost", new double[] { 0, 0, 0, 0, 0, 1, 2, 4, 5, 6 });
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true)
	private String name;

	private double[] gains;

	private boolean builtIn;

	public EqProfile() {
	}

	public EqProfile(String name, double[] gains, boolean builtIn) {
		this.name = name;
		this.gains = gains;
		this.builtIn = builtIn;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public double[] getGains() {
		return gains;
	}

	public void setGains(double[] gains) {
		this.gains = gains;
	}

	public boolean isBuiltIn() {
		return builtIn;
	}
}
