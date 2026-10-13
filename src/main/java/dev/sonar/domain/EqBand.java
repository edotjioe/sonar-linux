package dev.sonar.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * One parametric EQ band: a filter-chain builtin biquad ({@code bq_<type>}). gain is ignored by lowpass/highpass.
 * Stored as JSON in its entity.
 */
public record EqBand(String type, double freq, double gain, double q) {

	public static final Set<String> TYPES = Set.of("peaking", "lowshelf", "highshelf", "lowpass", "highpass");

	/** An EQ has 0..MAX_BANDS bands. freq/gain/Q change live; adding/removing a band or changing a type restarts it. */
	public static final int MAX_BANDS = 10;

	public static final double MAX_GAIN = 12;

	/** Old graphic-EQ band centres (Hz): how pre-parametric data converts. */
	private static final int[] GRAPHIC_FREQS = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };

	/** The old one-octave graphic EQ (also the shape of rows saved before bands existed). */
	public static List<EqBand> graphic(double... gains) {
		return IntStream.range(0, GRAPHIC_FREQS.length)
				.mapToObj(i -> new EqBand("peaking", GRAPHIC_FREQS[i], i < gains.length ? gains[i] : 0, 1.41)).toList();
	}

	/** A row saved before parametric bands: its graphic EQ minus the 0 dB bands (they do nothing). null = flat. */
	public static List<EqBand> fromLegacy(double[] gains) {
		return gains == null ? List.of() : graphic(gains).stream().filter(b -> b.gain() != 0).toList();
	}

	/** Validate and clamp a whole EQ from the API. */
	public static List<EqBand> check(List<EqBand> bands) {
		if (bands == null || bands.size() > MAX_BANDS) {
			throw new IllegalArgumentException("EQ takes at most " + MAX_BANDS + " bands");
		}
		return bands.stream().map(EqBand::checked).toList();
	}

	private EqBand checked() {
		if (!TYPES.contains(type)) {
			throw new IllegalArgumentException("Unknown EQ band type: " + type);
		}
		return new EqBand(type, clamp(freq, 20, 20000), clamp(gain, -MAX_GAIN, MAX_GAIN), clamp(q, 0.1, 10));
	}

	private static double clamp(double v, double min, double max) {
		return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
	}
}
