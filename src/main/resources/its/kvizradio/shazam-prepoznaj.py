#!/usr/bin/env python3
"""
Most ka shazamio-u.

Uzme URL strima (ili putanju do fajla), ffmpeg-om snimi kratak isecak i preda
ga shazamio-u. Ispisuje jednu JSON liniju: {"izvodjac": ..., "naslov": ...}
ili {"greska": ...}.

Odvojen proces je namerno: shazamio je Python i asinhron, a aplikacija je
JavaFX - isto kao sto HUB zove yt-dlp i ffmpeg spolja.
"""
import asyncio
import json
import os
import subprocess
import sys
import tempfile

SEKUNDI = 12


def isecak(izvor: str) -> str:
    """
    ffmpeg zna i HTTP strim i HLS, pa se isecak uzima uvek na isti nacin.

    Probano je i duze snimanje pa slanje samo kraja, zbog Icecast burst bafera -
    rezultat je bio isti, a cekalo se deset sekundi duze, pa je ostao jedan prolaz.
    """
    fajl = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    fajl.close()
    subprocess.run([os.environ.get("FFMPEG", "ffmpeg"), "-y", "-loglevel", "error",
                    "-i", izvor, "-t", str(SEKUNDI), "-ac", "1", "-ar", "44100",
                    "-f", "wav", fajl.name],
                   check=True, timeout=SEKUNDI + 45,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return fajl.name


async def prepoznaj(putanja: str) -> dict:
    from shazamio import Shazam
    odgovor = await Shazam().recognize(putanja)
    trag = odgovor.get("track")
    if not trag:
        return {"greska": "Pesma nije prepoznata."}
    return {"izvodjac": trag.get("subtitle", ""), "naslov": trag.get("title", "")}


def main() -> int:
    if len(sys.argv) < 2:
        print(json.dumps({"greska": "Nedostaje URL strima."}))
        return 1
    izvor = sys.argv[1]
    privremeni = []
    try:
        if izvor.startswith("http"):
            putanja = isecak(izvor)
            privremeni = [putanja]
        else:
            putanja = izvor
        print(json.dumps(asyncio.run(prepoznaj(putanja)), ensure_ascii=False))
        return 0
    except subprocess.CalledProcessError as e:
        poruka = (e.stderr or b"").decode("utf-8", "replace").strip().splitlines()
        print(json.dumps({"greska": "Snimanje isecka nije uspelo: "
                                    + (poruka[-1] if poruka else "ffmpeg greska")}))
        return 1
    except Exception as e:  # noqa: BLE001 - poruka ide korisniku, ne u log
        print(json.dumps({"greska": f"{type(e).__name__}: {e}"}, ensure_ascii=False))
        return 1
    finally:
        for f in privremeni:
            if os.path.exists(f):
                os.unlink(f)


if __name__ == "__main__":
    sys.exit(main())
