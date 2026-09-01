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
- sekcije: Domace (Pop/Rock/Folk/Ex-Yu), Zanrovi, Bez reklama, Omiljene
- precice: `Space` play/stop, `F` fade out, strelice jacina

## Zahtevi

- Java 17+
- Maven
- **VLC instaliran** (libvlc) - `sudo apt install vlc` odnosno VLC za Windows

## Pokretanje

```bash
mvn clean package
mvn javafx:run
```

CLI provera API-ja i zvuka, bez UI-ja:

```bash
java -cp "target/classes:target/libs/*" its.kvizradio.Cli -rs -n 10 -sviraj 20
java -cp "target/classes:target/libs/*" its.kvizradio.Cli -tag jazz -sviraj 0
```

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
