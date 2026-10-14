#!/bin/sh
# Update an installed Sonar: pull, rebuild the backend (tests included) and restart it. The overlay
# script restarts itself on the next Meta+S once it sees its file changed.
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

# Java 21 for ./mvnw: same lookup as install.sh
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/java}
[ -x "$JAVA" ] || JAVA=$(command -v java || true)
[ -x "$JAVA" ] || JAVA=$HOME/.local/share/jdk-21/bin/java
[ -x "$JAVA" ] || { echo "Java 21 not found: set JAVA_HOME, or unpack a JDK into ~/.local/share/jdk-21" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$JAVA")")")}

before=$(git rev-parse HEAD)
git pull --ff-only
if [ "$(git rev-parse HEAD)" = "$before" ] && [ "$1" != "--force" ]; then
	echo "Already up to date (--force rebuilds anyway)"
	exit 0
fi

# stop first: replacing the jar under a running JVM breaks its shutdown cleanup. Tests run before the
# jar is written, so a failed build leaves the old jar in place and that one is started again.
systemctl --user stop sonar.service
if ! ./mvnw -q package; then
	systemctl --user start sonar.service
	echo "Build failed: still running the previous version" >&2
	exit 1
fi

# the service file and launchers are templates in install/: when they changed, re-install them
if ! git diff --quiet "$before" HEAD -- install/; then
	install/install.sh
else
	systemctl --user start sonar.service
fi
echo "Sonar updated to $(sed -n 's#^\t<version>\(.*\)</version>#\1#p' pom.xml)"
