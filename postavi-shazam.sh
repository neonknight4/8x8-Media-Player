#!/usr/bin/env bash
# Postavlja prepoznavanje pesme na Linuxu.
#
# shazamio ne moze u sistemski Python: Debian i Ubuntu od 24.04 imaju
# EXTERNALLY-MANAGED (PEP 668), pa pip odbija instalaciju van venv-a. Venv se
# zato pravi na dogovorenom mestu - ~/.config/KvizRadio/venv - koje aplikacija
# sama nadje, tako da se u kvizradio.properties ne upisuje nista.
#
# Windows instaler ovo ne treba: on nosi svoj Python sa shazamio-om.
set -euo pipefail

# $HOME/.config fiksno, bez XDG_CONFIG_HOME: aplikacija folder podesavanja
# racuna iz user.home (Alati.podesavanjaFolder), pa bi venv na drugom mestu
# ostao nenadjen.
VENV="$HOME/.config/KvizRadio/venv"

if ! command -v ffmpeg >/dev/null; then
    echo "Nedostaje ffmpeg. Instaliraj pa pokreni ponovo:"
    echo "  sudo apt install ffmpeg"
    exit 1
fi

if ! python3 -c "import venv" 2>/dev/null; then
    echo "Nedostaje venv modul. Instaliraj pa pokreni ponovo:"
    echo "  sudo apt install python3-venv"
    exit 1
fi

if [ ! -x "$VENV/bin/python" ]; then
    echo "Pravim venv u $VENV ..."
    mkdir -p "$(dirname "$VENV")"
    python3 -m venv "$VENV"
fi

echo "Instaliram shazamio ..."
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet shazamio

# provera da paket zaista moze da se ucita, ne samo da je pip prijavio uspeh
"$VENV/bin/python" -c "import shazamio"

echo
echo "Gotovo. Dugme PREPOZNAJ radi, ne podesava se nista drugo."
echo "  python: $VENV/bin/python"
echo "  ffmpeg: $(command -v ffmpeg)"
