#!/usr/bin/env bash
# claude-watch relay installer. Run on the VPS as root:
#   curl -fsSL https://raw.githubusercontent.com/Slava-Gusak/claude-watch/main/relay/install.sh | sudo bash
# Safe to run again (updates relay.py, keeps tokens and login).
set -euo pipefail

DOMAIN="${CLAUDE_WATCH_DOMAIN:-mine.slava-gusak.ru}"
REPO_RAW="https://raw.githubusercontent.com/Slava-Gusak/claude-watch/main"
DIR=/opt/claude-watch

[ "$(id -u)" = 0 ] || { echo "Запусти через sudo"; exit 1; }
command -v python3 >/dev/null || { apt-get update -qq && apt-get install -y -qq python3; }

mkdir -p "$DIR" && chmod 700 "$DIR"
curl -fsSL "$REPO_RAW/relay/relay.py" -o "$DIR/relay.py"

# ---- TLS certificate (Let's Encrypt)
CERT="/etc/letsencrypt/live/$DOMAIN/fullchain.pem"
KEY="/etc/letsencrypt/live/$DOMAIN/privkey.pem"
if [ ! -f "$CERT" ]; then
  echo ">> Получаю сертификат для $DOMAIN"
  command -v certbot >/dev/null || { apt-get update -qq && apt-get install -y -qq certbot; }
  if ! ss -ltnH 'sport = :80' | grep -q .; then
    certbot certonly --standalone -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email
  elif systemctl is-active --quiet nginx; then
    apt-get install -y -qq python3-certbot-nginx
    certbot certonly --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email
  else
    echo "!! Порт 80 занят не nginx — не могу получить сертификат автоматически:"; ss -ltnp 'sport = :80'; exit 1
  fi
fi
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl restart claude-watch 2>/dev/null || true\n' > /etc/letsencrypt/renewal-hooks/deploy/claude-watch.sh
chmod +x /etc/letsencrypt/renewal-hooks/deploy/claude-watch.sh

# ---- config (generated once)
if [ ! -f "$DIR/config.json" ]; then
  PORT=""
  for p in 8443 9443 10443 18443; do
    ss -ltnH "sport = :$p" | grep -q . || { PORT=$p; break; }
  done
  [ -n "$PORT" ] || { echo "!! Нет свободного порта"; exit 1; }
  python3 - "$DIR/config.json" "$PORT" "$CERT" "$KEY" <<'EOF'
import json, secrets, sys
path, port, cert, key = sys.argv[1:]
json.dump({"host": "0.0.0.0", "port": int(port), "cert": cert, "key": key,
           "admin_token": secrets.token_urlsafe(18), "watch_token": secrets.token_urlsafe(24),
           "pc_token": secrets.token_urlsafe(24)}, open(path, "w"), indent=1)
EOF
  chmod 600 "$DIR/config.json"
fi
PORT=$(python3 -c "import json;print(json.load(open('$DIR/config.json'))['port'])")
ADMIN=$(python3 -c "import json;print(json.load(open('$DIR/config.json'))['admin_token'])")

# ---- firewall
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then ufw allow "$PORT/tcp" >/dev/null; fi

# ---- service
cat > /etc/systemd/system/claude-watch.service <<EOF
[Unit]
Description=claude-watch relay (Claude limits for the watch)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 $DIR/relay.py
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$DIR
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now claude-watch >/dev/null
systemctl restart claude-watch
sleep 2
systemctl is-active --quiet claude-watch || { journalctl -u claude-watch -n 30 --no-pager; exit 1; }

echo
echo "=============================================================="
echo " Готово. Открой на телефоне (никому не показывай эту ссылку):"
echo
echo "   https://$DOMAIN:$PORT/admin?key=$ADMIN"
echo
echo "=============================================================="
