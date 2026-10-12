#!/bin/sh
# Install Sonar for the current user: backend service (starts at login), launchers (Meta+S overlay),
# icon. Re-run after moving the repo. The udev rule for the hardware ChatMix dial needs root: see the end.
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
APPS=$HOME/.local/share/applications
UNITS=$HOME/.config/systemd/user

[ -f "$REPO/target/sonar.jar" ] || (cd "$REPO" && ./mvnw -q package -DskipTests)

mkdir -p "$UNITS" "$APPS" "$HOME/.local/share/icons/hicolor/256x256/apps"
# the templates say @REPO@ wherever the repo's path goes
sed "s#@REPO@#$REPO#g" "$REPO/install/sonar.service" > "$UNITS/sonar.service"
for f in dev.edo.sonar.desktop dev.edo.sonar.overlay.desktop; do
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
