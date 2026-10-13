package dev.sonar.domain;

import java.util.List;

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

	private int position = 0;

	/** Before parametric bands: one gain per graphic band. Only read when eqBands is null. */
	@Column(name = "eq")
	private double[] legacyEq;

	/** Current EQ; null (rows from before parametric bands) = converted from legacyEq. */
	@JdbcTypeCode(SqlTypes.JSON)
	private List<EqBand> eqBands;

	/** Bass, Voice, Treble gains (dB), see {@link EqBand#tone}; null (older rows) = all 0. */
	private double[] tone;

	/** Input channel (virtual mic) instead of an output channel. */
	@Column(columnDefinition = "boolean default false")
	private boolean mic = false;

	/** Mic only: rnnoise noise suppression in front of the EQ. */
	@Column(columnDefinition = "boolean default false")
	private boolean noiseSuppression = false;

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
