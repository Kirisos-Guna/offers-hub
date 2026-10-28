#!/usr/bin/env bash
# Installs or updates the Offers Hub listener on a Debian/Ubuntu VM (e.g. Google Cloud e2-micro).
#
#   curl -fsSL https://raw.githubusercontent.com/Kirisos-Guna/offers-hub/main/deploy/setup_vm.sh | sudo bash
#
# Then, once: sudo -u offershub /opt/offershub/backend/.venv/bin/python /opt/offershub/backend/login.py --env
# (logs in to Telegram and stores the session in backend/.env), and start the service.
set -euo pipefail

REPO="${REPO:-https://github.com/Kirisos-Guna/offers-hub.git}"
APP_DIR=/opt/offershub
APP_USER=offershub

apt-get update -qq
apt-get install -y -qq git python3 python3-venv > /dev/null

# e2-micro has 1 GB of RAM; a little swap keeps pip installs and image work from running out.
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap -q /swapfile && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

id -u "$APP_USER" > /dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"

if [ -d "$APP_DIR/.git" ]; then
  sudo -u "$APP_USER" git -C "$APP_DIR" pull -q --ff-only
else
  mkdir -p "$APP_DIR" && chown "$APP_USER" "$APP_DIR"
  sudo -u "$APP_USER" git clone -q "$REPO" "$APP_DIR"
fi

sudo -u "$APP_USER" python3 -m venv "$APP_DIR/backend/.venv"
sudo -u "$APP_USER" "$APP_DIR/backend/.venv/bin/pip" install -q --upgrade pip
sudo -u "$APP_USER" "$APP_DIR/backend/.venv/bin/pip" install -q -r "$APP_DIR/backend/requirements.txt"

install -m 644 "$APP_DIR/deploy/offershub-listener.service" /etc/systemd/system/offershub-listener.service
systemctl daemon-reload
systemctl enable -q offershub-listener

if grep -q '^TG_SESSION_STRING=.' "$APP_DIR/backend/.env" 2>/dev/null; then
  systemctl restart offershub-listener
  echo "Listener (re)started. Logs: journalctl -u offershub-listener -f"
else
  echo "Installed. Next: create $APP_DIR/backend/.env (see .env.example) and log in to Telegram:"
  echo "  sudo -u $APP_USER $APP_DIR/backend/.venv/bin/python $APP_DIR/backend/login.py --env"
  echo "  sudo systemctl start offershub-listener"
fi
