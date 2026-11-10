#!/bin/sh
# Install Sonar for the current user: backend service (starts at login), launchers (Meta+S overlay),
# icon, tray icon at login. Re-run after moving the repo. The udev rule for the hardware ChatMix dial needs root: see the end.
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
APPS=$HOME/.local/share/applications
UNITS=$HOME/.config/systemd/user

# native binary with GraalVM, else the jar on Java 21
. "$REPO/install/toolchain.sh"

[ -f "$REPO/$ARTIFACT" ] || (cd "$REPO" && $BUILD -DskipTests)

mkdir -p "$UNITS" "$APPS" "$HOME/.local/share/icons/hicolor/256x256/apps"
# the templates say @REPO@ wherever the repo's path goes
sed -e "s#@REPO@#$REPO#g" -e "s#@EXEC@#$EXEC#g" "$REPO/install/sonar.service" > "$UNITS/sonar.service"
# auto-update: installed but left as it was (off until switched on in Sonar's Preferences)
sed "s#@REPO@#$REPO#g" "$REPO/install/sonar-update.service" > "$UNITS/sonar-update.service"
cp "$REPO/install/sonar-update.timer" "$UNITS/sonar-update.timer"
for f in dev.sonar.desktop dev.sonar.overlay.desktop; do
	sed "s#@REPO@#$REPO#g" "$REPO/install/$f" > "$APPS/$f"
done
cp "$REPO/sonar.png" "$HOME/.local/share/icons/hicolor/256x256/apps/sonar.png"
# the window process, hidden, so the tray icon is there from login
mkdir -p "$HOME/.config/autostart"
sed "s#@REPO@#$REPO#g" "$REPO/install/dev.sonar.autostart.desktop" > "$HOME/.config/autostart/dev.sonar.desktop"

systemctl --user daemon-reload
systemctl --user enable sonar.service
systemctl --user restart sonar.service
command -v kbuildsycoca6 >/dev/null && kbuildsycoca6 >/dev/null 2>&1 || true  # KDE: pick up the launchers / Meta+S

echo "Sonar installed: http://localhost:8080"
echo "Hardware ChatMix dial (Arctis Nova Pro Wireless) needs, once:"
echo "  sudo cp $REPO/udev/70-steelseries-arctis.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger"
