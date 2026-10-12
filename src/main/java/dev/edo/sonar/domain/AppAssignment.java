package dev.edo.sonar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** Remembers which channel an application (matched by process binary name) belongs to. */
@Entity
public class AppAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true)
	private String binaryName;

	private String displayName;

	@ManyToOne(fetch = FetchType.EAGER)
	private Channel channel;

	public Long getId() {
		return id;
	}

	public String getBinaryName() {
		return binaryName;
	}

	public void setBinaryName(String binaryName) {
		this.binaryName = binaryName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public Channel getChannel() {
		return channel;
	}

	public void setChannel(Channel channel) {
		this.channel = channel;
	}
}
