package dev.sonar.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A mixer channel. Output (Game/Chat/Media/Aux): a null-sink, plus an EQ process that plays
 * its monitor into a physical device. Mic: a virtual source (also a null-sink module), plus an EQ
 * process that reads a physical mic into it, running only while something records from it.
 */
@Entity
public class Channel {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true)
	private String name;

	@Column(unique = true)
	private String sinkName;

	/** PipeWire module id of the null-sink; -1 when not loaded. */
	private long nullSinkModule = -1;

	private String deviceName;

	private int volume = 100;

	private boolean muted = false;

	/** Streamer mode: this channel in the stream mix (output channels only), independent of volume/muted. */
	@Column(columnDefinition = "integer default 100")
	private int streamVolume = 100;

	@Column(columnDefinition = "boolean default false")
	private boolean streamMuted = false;

	private int position = 0;

	/** Before parametric bands: one gain per graphic band. Only read when eqBands is null. */
	@Column(name = "eq")
	private double[] legacyEq;

	/** Current EQ; null (rows from before parametric bands) = converted from legacyEq. */
	@JdbcTypeCode(SqlTypes.JSON)
	private List<EqBand> eqBands;

	/** Bass, Voice, Treble gains (dB), see {@link EqBand#tone}; null (older rows) = all 0. */
	private double[] tone;

	/** EQ preamp (dB, {@link EqBand#checkPreamp}): a gain before the bands, e.g. AutoEq's headroom for its boosts. */
	@Column(columnDefinition = "double precision default 0")
	private double preamp;

	public double getPreamp() {
		return preamp;
	}

	public void setPreamp(double preamp) {
		this.preamp = preamp;
	}

	/** An EQ as remembered for one output: see {@link #eqByOutput}. */
	public record SavedEq(List<EqBand> bands, double[] tone, double preamp) {
	}

	/**
	 * The EQ this channel had on each output it played to (key: MixerService.outputKey), so it comes back when
	 * the channel returns there. The current output's EQ is the fields above; it's stored here when the output changes.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, SavedEq> eqByOutput;

	/** The output key the current EQ belongs to; null until first known. */
	private String eqOutput;

	public Map<String, SavedEq> getEqByOutput() {
		if (eqByOutput == null) {
			eqByOutput = new HashMap<>();
		}
		return eqByOutput;
	}

	public String getEqOutput() {
		return eqOutput;
	}

	public void setEqOutput(String eqOutput) {
		this.eqOutput = eqOutput;
	}

	/** Input channel (virtual mic) instead of an output channel. */
	@Column(columnDefinition = "boolean default false")
	private boolean mic = false;

	/** Mic only: rnnoise noise suppression in front of the EQ. */
	@Column(columnDefinition = "boolean default false")
	private boolean noiseSuppression = false;

	/** Output only: virtual 7.1 surround for headphones (the sink becomes 7.1, the EQ process adds an HRTF stage). */
	@Column(columnDefinition = "boolean default false")
	private boolean spatial = false;

	public boolean isSpatial() {
		return spatial;
	}

	public void setSpatial(boolean spatial) {
		this.spatial = spatial;
	}

	public boolean isNoiseSuppression() {
		return noiseSuppression;
	}

	public void setNoiseSuppression(boolean noiseSuppression) {
		this.noiseSuppression = noiseSuppression;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSinkName() {
		return sinkName;
	}

	public void setSinkName(String sinkName) {
		this.sinkName = sinkName;
	}

	public long getNullSinkModule() {
		return nullSinkModule;
	}

	public void setNullSinkModule(long nullSinkModule) {
		this.nullSinkModule = nullSinkModule;
	}

	public List<EqBand> getEq() {
		return eqBands != null ? eqBands : EqBand.fromLegacy(legacyEq);
	}

	public double[] getTone() {
		return tone == null ? new double[3] : tone;
	}

	public void setTone(double[] tone) {
		this.tone = tone;
	}

	public void setEq(List<EqBand> eq) {
		this.eqBands = eq;
	}

	public String getDeviceName() {
		return deviceName;
	}

	public void setDeviceName(String deviceName) {
		this.deviceName = deviceName;
	}

	public int getVolume() {
		return volume;
	}

	public void setVolume(int volume) {
		this.volume = volume;
	}

	public boolean isMuted() {
		return muted;
	}

	public void setMuted(boolean muted) {
		this.muted = muted;
	}

	public int getStreamVolume() {
		return streamVolume;
	}

	public void setStreamVolume(int streamVolume) {
		this.streamVolume = streamVolume;
	}

	public boolean isStreamMuted() {
		return streamMuted;
	}

	public void setStreamMuted(boolean streamMuted) {
		this.streamMuted = streamMuted;
	}

	public int getPosition() {
		return position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public boolean isMic() {
		return mic;
	}

	public void setMic(boolean mic) {
		this.mic = mic;
	}
}
