package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class BluetoothBatteriesTest {

	private static final String UPOWER = """
  native-path:          /org/bluez/hci0/dev_4C_87_5D_82_02_31
  model:                Bose QC35 II
  serial:               4C:87:5D:82:02:31
  power supply:         no
  updated:              di 13 okt 2026 13:15:42 CEST (42 seconds ago)
  has history:          yes
  has statistics:       no
  headphones
    warning-level:       none
    percentage:          90%
    icon-name:          'battery-missing-symbolic'
	""";

	@Test
	void parseRealUpowerOutput() {
		assertEquals(new BluetoothBatteries.Battery("Bose QC35 II", 90), BluetoothBatteries.parse(UPOWER));
	}

	@Test
	void parseWithoutPercentageIsNull() {
		assertNull(BluetoothBatteries.parse(removeLine(UPOWER, "percentage:")));
	}

	@Test
	void parseWithoutModelUsesDefaultName() {
		assertEquals(new BluetoothBatteries.Battery("Headphones", 90), BluetoothBatteries.parse(removeLine(UPOWER, "model:")));
	}

	private static String removeLine(String text, String substring) {
		return text.lines().filter(l -> !l.contains(substring)).collect(Collectors.joining("\n"));
	}
}
