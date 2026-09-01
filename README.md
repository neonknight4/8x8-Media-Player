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
  salju dugme PREPOZNAJ (shazamio, besplatno)
- mute pored VOL; fade out; sekcije Domace (Pop/Rock/Folk/Ex-Yu), Zanrovi,
  Bez reklama, Omiljene, Sakrivene
- **grupe omiljenih** (Pauza, Zagrevanje...): desni klik na karticu u omiljenima
- **spektar** u donjem baru dok muzika svira - zlatne trake, brojevi iz VLC-a
- precice: `Space` play/stop, `F` fade out, strelice jacina

## Naziv pesme

Prvo se cita sa samog strima, iz ICY metapodataka (`StreamTitle`). To se radi
sopstvenim zahtevom, ne preko VLC-a: mereno na istim stanicama, vlcj daje naziv
za 5 od 14, a direktno citanje za 7 od 13 - ukljucujuci SomaFM i SWR3, gde vlcj
vraca prazno. Cita se jedan blok pa se veza zatvara, na svakih 15 sekundi, da se
ne trosi slusalacko mesto na manjim stanicama.

Za stanice koje naziv uopste ne salju (OK radio, Naxi, Pink, 202...) postoji
dugme **PREPOZNAJ** u donjem baru.

Prepoznaje se preko biblioteke [shazamio](https://github.com/shazamio/ShazamIO),
u zasebnom Python procesu - isto kao sto HUB zove yt-dlp i ffmpeg. Skripta snimi
12 sekundi strima i vrati izvodjaca i naslov.

**Koristi Shazamov nezvanicni API.** To krsi njihove uslove koriscenja i puca kad
promene protokol - za licnu upotrebu je to tvoja odluka, ne za deljenje dalje.

Mereno, u istom trenutku sa onim sto javlja sam strim:

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
# ~/.config/KvizRadio/kvizradio.properties, odnosno %APPDATA%\KvizRadio
prepoznavanje.python=/putanja/do/.venv-shazam/bin/python
```

Prazno `prepoznavanje.python` znaci `python3` (Linux) odnosno `python` (Windows).

Na Windowsu isto: Python 3 sa `shazamio`, plus `ffmpeg` na PATH-u ili
`ffmpeg.exe` pored `KvizRadio.exe` (aplikacija ga tada sama prosledi skripti).
Instaler ga ne nosi.

Probani su i **AudD** (placen, radjen bas za radio) i **AcoustID** (besplatan) -
oba izbacena. AcoustID za radio ne radi: na SomaFM strimu gde ICY javlja tacan
naziv (`Electric Skychurch - Heaven`) vraca `{"results": []}` i za 20s i za 60s
otiska, jer indeksira otiske **celih snimaka** i poredi ih po trajanju. To se ne
popravlja podesavanjem.

## Zahtevi

### Linux

Provereno na Ubuntu 24.04. Sve sto treba da player radi:

```bash
sudo apt install openjdk-21-jdk maven vlc
```

| paket | zasto |
|---|---|
| `openjdk-21-jdk` | projekat se gradi za Javu 17, radi i na 21 |
| `maven` | build |
| `vlc` | donosi `libvlc.so.5` i dekodere - vlcj svira preko njega, `javafx.scene.media` ne ume Icecast pouzdano |

`libvlc-dev` ne treba: vlcj trazi po obrascu `libvlc\.so(?:\.\d)*`, sto hvata i
`libvlc.so.5` koji dolazi uz `vlc`. Provera:

```bash
ls -l /usr/lib/x86_64-linux-gnu/libvlc.so*
```

Ako toga nema, aplikacija se ne pokrece nego javi da VLC nije nadjen.

#### Prepoznavanje pesme (opciono)

Samo ako hoces dugme PREPOZNAJ; naziv pesme iz samog strima radi i bez ovoga.

```bash
sudo apt install python3-venv ffmpeg
python3 -m venv .venv-shazam
.venv-shazam/bin/pip install shazamio
```

```properties
# ~/.config/KvizRadio/kvizradio.properties
prepoznavanje.python=/putanja/do/8x8-Media-Player/.venv-shazam/bin/python
```

### Windows

- JDK 17+ i Maven za build; JDK 21 + WiX 3.x za pravljenje instalera
- VLC ne mora rucno: instaler nosi svoj libvlc (vidi *Windows instaler*)
- za prepoznavanje pesme ne treba nista rucno: instaler nosi svoj Python sa
  `shazamio`-om i `ffmpeg.exe` (vidi *Windows instaler*). Rucno se postavlja
  samo ako se pokrece iz razvojnog okruzenja, bez instalera.

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

Instaler nosi sve sto aplikaciji treba - na tudjem laptopu u kafani se ne racuna
na to da je bilo sta instalirano, ni koja je verzija. Skripta sve skine sama pri
prvom pokretanju, u `tools\` (gitignore-ovan):

| sta | gde zavrsi | zasto |
|---|---|---|
| **VLC** `libvlc.dll`, `libvlccore.dll`, `plugins\` | `app\vlc\` | sviranje; kao sto HUB nosi yt-dlp |
| **Python 3.12 embeddable** + `shazamio` | `app\python\` | prepoznavanje pesme bez instaliranog Pythona |
| **ffmpeg.exe** | `app\` | skripta njime snima isecak strima |

Python je 3.12, ne 3.13: `numpy` i `aiohttp` imaju gotove `cp312` wheel-ove za
`win_amd64`, pa pip nista ne kompajlira na build masini. Embeddable distribucija
nema pip i **ne gleda `site-packages`** dok se u `python312._pth` ne odkomentarise
`import site` - bez toga `import shazamio` puca iako je paket na disku. Build to
radi sam, pa proveri sa `python.exe -c "import shazamio"` i pukne ako ne prolazi.

Aplikacija bundlovani Python nalazi preko `Alati.nadjiFolder("python")`, isto kao
VLC. `prepoznavanje.python` u konfiguraciji i dalje pretegne, ako hoces svoj.

Cena je velicina: instaler poraste sa ~100 MB na ~320 MB.

VLC je pinovan na **3.0.x**: vlcj 4 radi sa libvlc 3, sa libvlc 4 ne.

Isto radi i GitHub Actions (`.github/workflows/windows-installer.yml`), rucno
ili na tag `v1.1`; instaler ide kao Release asset.

## Spektar

VLC-ov modul `visual` crta spektar kao sliku; aplikacija iz te slike cita samo
visine po opsezima, a trake crta sama - zlatno na crnom, u istim bojama kao
ostatak. Sama VLC-ova slika (sarena, zeleno-zuta) se nigde ne prikazuje.

Dve stvari koje su morale da se rese:

- opcije idu na **fabriku** (instancu libvlc-a), ne na medij. Kao opcije medija
  se ne primene i ne stigne nijedan kadar;
- radijski zvuk je jako kompresovan, pa sve trake stoje izmedju 0.7 i 0.8 -
  bez rastezanja opsega se ne bi videlo da se mrdaju. Zato UI vodi klizni
  minimum i maksimum, pa preko njih rasteze prikaz.

## Podesavanja

Stoje van instalacije, u `%APPDATA%\KvizRadio` (Windows) odnosno
`~/.config/KvizRadio` (Linux):

| fajl | sta |
|---|---|
| `kvizradio.properties` | levi meni: grupe, stavke, tagovi po stavci |
| `mreze.json` | mreze bez reklama (Radio Caprice, SomaFM, Radio Paradise...) |
| `omiljene.json` | omiljene stanice |
| `omiljene-grupe.json` | koja omiljena stanica ide u koju grupu |
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
