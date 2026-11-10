#!/bin/sh
# Update an installed Sonar: pull, rebuild the backend (tests included) and restart it. The overlay
# script restarts itself on the next Meta+S once it sees its file changed.
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

# native binary with GraalVM, else the jar on Java 21: same as install.sh
. "$REPO/install/toolchain.sh"

before=$(git rev-parse HEAD)
git pull --ff-only
if [ "$(git rev-parse HEAD)" = "$before" ] && [ "$1" != "--force" ]; then
	echo "Already up to date (--force rebuilds anyway)"
	exit 0
fi

if [ -n "$NATIVE" ]; then
	# native-image takes minutes, so build while the old binary keeps running: moved aside, as Linux
	# won't overwrite a running executable. Tests run first; a failed build puts the old one back.
	[ -f target/sonar ] && mv target/sonar target/sonar.old
	if ! $BUILD; then
		[ -f target/sonar.old ] && mv target/sonar.old target/sonar
		echo "Build failed: still running the previous version" >&2
		exit 1
	fi
	systemctl --user stop sonar.service
	rm -f target/sonar.old
else
	# stop first: replacing the jar under a running JVM breaks its shutdown cleanup. Tests run before the
	# jar is written, so a failed build leaves the old jar in place and that one is started again.
	systemctl --user stop sonar.service
	if ! $BUILD; then
		systemctl --user start sonar.service
		echo "Build failed: still running the previous version" >&2
		exit 1
	fi
fi

# the service file and launchers are templates in install/: when they changed, or the installed service
# runs something else (GraalVM installed or removed since), re-install them
if ! git diff --quiet "$before" HEAD -- install/ \
		|| ! grep -qxF "ExecStart=$EXEC" "$HOME/.config/systemd/user/sonar.service"; then
	install/install.sh
else
	systemctl --user start sonar.service
fi
echo "Sonar updated to $(sed -n 's#^\t<version>\(.*\)</version>#\1#p' pom.xml)"
