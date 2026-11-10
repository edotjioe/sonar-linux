# Sourced by install.sh and update.sh (after setting REPO): how Sonar is built and what the service runs.
# GraalVM 25 ($GRAALVM_HOME, else ~/.local/opt/graalvm-25): a native binary, target/sonar — starts in
# well under a second and needs a fraction of the JVM's memory. Without it: the jar on Java 21.

GRAALVM=${GRAALVM_HOME:-$HOME/.local/opt/graalvm-25}
if [ -x "$GRAALVM/bin/native-image" ]; then
	NATIVE=1
	export GRAALVM_HOME=$GRAALVM JAVA_HOME=$GRAALVM  # GraalVM's own JDK runs ./mvnw too
	BUILD="./mvnw -q -Pnative package native:compile"
	ARTIFACT=target/sonar
	# without a cap the heap grows towards a share of RAM; Sonar's runtime heap is ~30 MiB, as the image
	# heap (~115 MiB, Spring/Hibernate initialised at build time) holds what the JVM builds at startup
	EXEC="$REPO/target/sonar -Xmx64m"
else
	NATIVE=
	# Java 21: $JAVA_HOME, else the one on PATH, else a JDK unpacked in ~/.local/share/jdk-21 (e.g. on Bazzite)
	JAVA=${JAVA_HOME:+$JAVA_HOME/bin/java}
	[ -x "$JAVA" ] || JAVA=$(command -v java || true)
	[ -x "$JAVA" ] || JAVA=$HOME/.local/share/jdk-21/bin/java
	[ -x "$JAVA" ] || { echo "Java 21 not found: set JAVA_HOME, or unpack a JDK into ~/.local/share/jdk-21" >&2; exit 1; }
	export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$JAVA")")")}  # for ./mvnw
	BUILD="./mvnw -q package"
	ARTIFACT=target/sonar.jar
	# Small-app JVM: the default sizes for a big server (G1, heap up to 1/4 of RAM, both JIT tiers) cost
	# ~300 MiB more than Sonar needs; it uses ~70 MiB of heap and mostly waits on pactl.
	EXEC="$JAVA -Xms32m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -jar $REPO/target/sonar.jar"
fi
