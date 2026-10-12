package dev.edo.sonar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A mixer channel. Output (Game/Chat/Media/Aux): a null-sink, plus an EQ process that plays
 * its monitor into a physical device. Mic: just the EQ process, which reads a physical mic and
 * is itself the virtual mic source (no module: nullSinkModule stays -1).
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

	/** Current EQ curve, one gain (dB) per {@link EqProfile#BANDS}; null (rows from before EQ) = flat. */
	private double[] eq;

	/** Input channel (virtual mic) instead of an output channel. */
	@Column(columnDefinition = "boolean default false")
	private boolean mic = false;

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

	public double[] getEq() {
		return eq == null ? new double[EqProfile.BANDS.length] : eq;
	}

	public void setEq(double[] eq) {
		this.eq = eq;
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
