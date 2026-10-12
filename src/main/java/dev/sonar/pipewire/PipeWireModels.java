package dev.sonar.pipewire;

/** Plain data shapes parsed from PipeWire's CLI tools. */
public final class PipeWireModels {

	private PipeWireModels() {
	}

	/** A physical device: an output ("alsa_output.usb-...") or a mic ("alsa_input.usb-..."). */
	public record PhysicalSink(String name, String description) {
	}

	/** One running audio stream (an application playing into some sink). pid -1 / mediaRole null when unknown. */
	public record AppStream(int index, String binary, String appName, String sinkName, int pid, String mediaRole) {
	}

	/** A loaded PipeWire module (e.g. a channel's null-sink), as reported by `pactl`. */
	public record Module(long id, String name, String args) {
	}
}
