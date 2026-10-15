#!/bin/sh
# Install Sonar for the current user: backend service (starts at login), launchers (Meta+S overlay),
# icon. Re-run after moving the repo. The udev rule for the hardware ChatMix dial needs root: see the end.
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
APPS=$HOME/.local/share/applications
UNITS=$HOME/.config/systemd/user

# Java 21: $JAVA_HOME, else the one on PATH, else a JDK unpacked in ~/.local/share/jdk-21 (e.g. on Bazzite)
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/java}
[ -x "$JAVA" ] || JAVA=$(command -v java || true)
[ -x "$JAVA" ] || JAVA=$HOME/.local/share/jdk-21/bin/java
[ -x "$JAVA" ] || { echo "Java 21 not found: set JAVA_HOME, or unpack a JDK into ~/.local/share/jdk-21" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$JAVA")")")}  # for ./mvnw

[ -f "$REPO/target/sonar.jar" ] || (cd "$REPO" && ./mvnw -q package -DskipTests)

mkdir -p "$UNITS" "$APPS" "$HOME/.local/share/icons/hicolor/256x256/apps"
# the templates say @REPO@ wherever the repo's path goes
sed -e "s#@REPO@#$REPO#g" -e "s#@JAVA@#$JAVA#g" "$REPO/install/sonar.service" > "$UNITS/sonar.service"
# auto-update: installed but left as it was (off until switched on in Sonar's Preferences)
sed "s#@REPO@#$REPO#g" "$REPO/install/sonar-update.service" > "$UNITS/sonar-update.service"
cp "$REPO/install/sonar-update.timer" "$UNITS/sonar-update.timer"
for f in dev.sonar.desktop dev.sonar.overlay.desktop; do
	sed "s#@REPO@#$REPO#g" "$REPO/install/$f" > "$APPS/$f"
done
cp "$REPO/sonar.png" "$HOME/.local/share/icons/hicolor/256x256/apps/sonar.png"

systemctl --user daemon-reload
systemctl --user enable sonar.service
systemctl --user restart sonar.service
command -v kbuildsycoca6 >/dev/null && kbuildsycoca6 >/dev/null 2>&1 || true  # KDE: pick up the launchers / Meta+S

echo "Sonar installed: http://localhost:8080"
echo "Hardware ChatMix dial (Arctis Nova Pro Wireless) needs, once:"
echo "  sudo cp $REPO/udev/70-steelseries-arctis.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger"
