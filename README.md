# 8x8 Media Player — KvizRadio

Pustanje online radija tokom pab kviza: muzika izmedju rundi i u pauzama, sa
dugmetom koje voditelj moze da pritisne bez gledanja.

Isti stil kao [PabKviz8x8Hub](https://github.com/neonknight4/PabKviz8x8Hub)
(paket `its.*`, programski JavaFX UI, podesavanja u APPDATA), pa `radio` i
`player` paket mogu kasnije da udju u HUB kao modul - nijedna klasa u njima ne
zna za JavaFX.

## Sta radi

- **Radio Browser API** (https://api.radio-browser.info) - mirror discovery sa
  failoverom, pretraga po zanru / drzavi / imenu, brojac klikova, kes 24h
- **vlcj** (libvlc) za zvuk - `javafx.scene.media` ne svira Icecast pouzdano
- **auto-reconnect** kad strim pukne: 2s, 4s, 8s, 15s, pa svakih 30s
- **fade out** ~2s pa stop, i momentalni stop
- **naziv pesme** koja ide: iz ICY metapodataka strima, a za stanice koje ih ne
  salju dugme PREPOZNAJ (AcoustID, besplatno)
- mute pored VOL; fade out; sekcije Domace (Pop/Rock/Folk/Ex-Yu), Zanrovi,
  Bez reklama, Omiljene, Sakrivene
- precice: `Space` play/stop, `F` fade out, strelice jacina

## Naziv pesme

Prvo se cita sa samog strima, iz ICY metapodataka (`StreamTitle`). To se radi
sopstvenim zahtevom, ne preko VLC-a: mereno na istim stanicama, vlcj daje naziv
za 5 od 14, a direktno citanje za 7 od 13 - ukljucujuci SomaFM i SWR3, gde vlcj
vraca prazno. Cita se jedan blok pa se veza zatvara, na svakih 15 sekundi, da se
ne trosi slusalacko mesto na manjim stanicama.

Za stanice koje naziv uopste ne salju (OK radio, Naxi, Pink, 202...) postoji
dugme **PREPOZNAJ** u donjem baru.

| servis | cena | radi na radiju? |
|---|---|---|
| `shazam` | besplatno | da - najtacnije od probanog, ali vidi upozorenje |
| `audd` (podrazumevano) | 300 zahteva besplatno pri registraciji, pa $5/mesec za 1.000 | da, radjeno bas za to |
| `acoustid` | besplatno | **ne** - vidi merenje ispod |

### servis=shazam

Ide preko biblioteke [shazamio](https://github.com/shazamio/ShazamIO), u zasebnom
Python procesu - isto kao sto HUB zove yt-dlp i ffmpeg. Skripta snimi 12 sekundi
strima i vrati izvodjaca i naslov.

**Koristi Shazamov nezvanicni API.** To krsi njihove uslove koriscenja i puca kad
promene protokol. Zato nije podrazumevano i ne ide u instaler koji se deli dalje
- za licnu upotrebu je to tvoja odluka.

Mereно, u istom trenutku sa onim sto javlja sam strim:

| stanica | ICY kaze | Shazam kaze |
|---|---|---|
| Cool Radio | Henny - Sava i Dunav | Henny & Breskvica - Sava i Dunav |
| Naxi Radio | *(ne salje nista)* | Oliver Mandic - Poludecu |
| Yu Eco | Jelena Rozga - Ljubi me | *(nije prepoznata)* |

Priprema:

```bash
python3 -m venv .venv-shazam
.venv-shazam/bin/pip install shazamio
```

```properties
prepoznavanje.servis=shazam
prepoznavanje.python=/putanja/do/.venv-shazam/bin/python
```

Na Windowsu isto: Python 3 sa `shazamio`, plus `ffmpeg` na PATH-u ili
`ffmpeg.exe` pored `KvizRadio.exe` (aplikacija ga tada sama prosledi skripti).
Instaler ga ne nosi.

Jedno prepoznavanje je jedan klik na PREPOZNAJ, pa 300 besplatnih zahteva
izadje na nekoliko desetina kvizova.

```properties
# kvizradio.properties
prepoznavanje.servis=acoustid
prepoznavanje.apiKey=tvoj-kljuc
```

Bez kljuca dugme objasni gde se uzima, umesto da tiho ne radi. **Kljuc ne ide u
ovaj repo** - upisuje se u tvoju kopiju konfiguracije
(`~/.config/KvizRadio/kvizradio.properties`, odnosno `%APPDATA%\KvizRadio`).

Zasto AcoustID nije podrazumevan, iako je besplatan: probano na SomaFM strimu
gde ICY javlja tacan naziv (`Electric Skychurch - Heaven`), AcoustID vraca
`{"results": []}` i za 20s i za 60s otiska. On indeksira otiske **celih
snimaka** i poredi ih po trajanju, pa isecak sa radija nema sta da pogodi. To se
ne popravlja podesavanjem.

Shazam nema javni API - ono sto kruzi su rekonstruisani klijenti koji krse
njihove uslove i pucaju kad se protokol promeni.

## Zahtevi

- Java 17+
- Maven
- **VLC instaliran** (libvlc) - `sudo apt install vlc` odnosno VLC za Windows

## Pokretanje

```bash
mvn clean package
mvn javafx:run
```

U NetBeans-u Run radi direktno: glavna klasa je `its.kvizradio.Pokretac`, koja
ne nasledjuje `Application` - inace JVM odbija pokretanje sa classpath-a
porukom "JavaFX runtime components are missing".

CLI provera API-ja i zvuka, bez UI-ja:

```bash
java -cp "target/classes:target/libs/*" its.kvizradio.Cli -rs -n 10 -sviraj 20
java -cp "target/classes:target/libs/*" its.kvizradio.Cli -tag jazz -sviraj 0
```

## Windows instaler

```
build-windows.bat 1.0
```

Pokrece se **na Windowsu**, treba JDK 21 (jpackage), Maven i WiX Toolset 3.x.
Izlaz je `dist\KvizRadio-1.0.exe`.

Instaler nosi i **fpcalc** (Chromaprint) za prepoznavanje pesme, i **svoj VLC** (`libvlc.dll`, `libvlccore.dll`, `plugins\`) u
podfolderu `vlc`, kao sto HUB nosi yt-dlp i ffmpeg - na tudjem laptopu u kafani
se ne racuna na to da je VLC instaliran, ni koja je verzija. Skripta ga skine
sama pri prvom pokretanju.

VLC je pinovan na **3.0.x**: vlcj 4 radi sa libvlc 3, sa libvlc 4 ne.

Isto radi i GitHub Actions (`.github/workflows/windows-installer.yml`), rucno
ili na tag `v1.1`; instaler ide kao Release asset.

## Podesavanja

Stoje van instalacije, u `%APPDATA%\KvizRadio` (Windows) odnosno
`~/.config/KvizRadio` (Linux):

| fajl | sta |
|---|---|
| `kvizradio.properties` | levi meni: grupe, stavke, tagovi po stavci |
| `mreze.json` | mreze bez reklama (Radio Caprice, SomaFM, Radio Paradise...) |
| `omiljene.json` | omiljene stanice |
| `stanje.properties` | jacina i poslednja stanica |
| `kes/` | odgovori API-ja, vaze 24h |

Prve dve se prvi put prekopiraju iz aplikacije i posle su tvoje - tagovi na
Radio Browser-u su neuredni ("folk", "narodna", "turbo folk"), pa se dopunjuju
bez rekompajliranja.

## Struktura

```
its/kvizradio/
  radio/    RadioBrowserService, Stanica, Sekcija, Meni, Odeljak,
            BezReklama, FavoritesStore     - API i podaci, bez JavaFX-a
  player/   PlayerService                  - vlcj, bez JavaFX-a
  ui/       Sidebar, PlayerBar, Kartica    - JavaFX
  KvizRadioApp, Podesavanja, Alati, Json, Cli
```
