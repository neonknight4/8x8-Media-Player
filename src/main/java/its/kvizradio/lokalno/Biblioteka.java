package its.kvizradio.lokalno;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Muzika sa diska: koji su folderi zavedeni i sta je u njima.
 *
 * Folderi stoje u folderi.json, istim obrascem kao mreze.json - ugradjeni fajl
 * se prvi put prekopira korisniku, pa je posle njegov.
 *
 * Skeniranje cita tagove svakog fajla, sto za par hiljada pesama traje, pa se
 * rezultat kesira. Kes vazi dok se ne promeni nijedan folder ispod zadatog:
 * poredi se najskorija izmena <b>foldera</b> (ne fajlova), jer se datum foldera
 * menja kad se u njemu nesto doda, obrise ili preimenuje - a obilazak samo
 * foldera je hiljadu puta jeftiniji od citanja tagova.
 */
public final class Biblioteka {

    private static final String FAJL = "folderi.json";
    private static final List<String> NASTAVCI = List.of(".mp3", ".flac", ".m4a", ".ogg");

    private final Consumer<String> log;
    private final Path korisnicki;

    public Biblioteka(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
        this.korisnicki = Alati.podesavanjaFolder().resolve(FAJL);
        // jaudiotagger pri svakom fajlu ispisuje po nekoliko redova u log
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    /** Folderi iz konfiguracije, sa procitanim (ili kesiranim) sadrzajem. */
    public List<Folder> folderi() {
        List<Folder> folderi = new ArrayList<>();
        for (Object o : Json.lista(Json.put(Json.parsiraj(ucitajKonfiguraciju()), "folderi"))) {
            String naziv = Json.tekst(Json.mapa(o).get("naziv"));
            String putanja = Json.tekst(Json.mapa(o).get("putanja"));
            if (naziv.isBlank() || putanja.isBlank()) {
                continue;
            }
            Path koren = Path.of(putanja);
            folderi.add(new Folder(naziv, koren, Files.isDirectory(koren) ? numere(koren) : List.of()));
        }
        return folderi;
    }

    /** Jedan folder, bez zavodjenja u konfiguraciju - za CLI i za pregled pri dodavanju. */
    public Folder folder(String naziv, Path putanja) {
        return new Folder(naziv, putanja, Files.isDirectory(putanja) ? numere(putanja) : List.of());
    }

    /** Dopisuje folder u konfiguraciju; naziv se ne proverava, moze ih biti vise istih. */
    public void dodaj(String naziv, Path putanja) {
        StringBuilder sb = new StringBuilder("{\n  \"folderi\": [\n");
        List<String> zapisi = new ArrayList<>();
        for (Object o : Json.lista(Json.put(Json.parsiraj(ucitajKonfiguraciju()), "folderi"))) {
            zapisi.add(zapis(Json.tekst(Json.mapa(o).get("naziv")),
                    Json.tekst(Json.mapa(o).get("putanja"))));
        }
        zapisi.add(zapis(naziv, putanja.toString()));
        sb.append(String.join(",\n", zapisi)).append("\n  ]\n}\n");
        try {
            Alati.upisiAtomski(korisnicki, sb.toString());
        } catch (Exception e) {
            log.accept("ERROR: " + FAJL + " nije snimljen (" + e.getMessage() + ")");
        }
    }

    private static String zapis(String naziv, String putanja) {
        return "    {\"naziv\": " + Json.navodnici(naziv)
                + ", \"putanja\": " + Json.navodnici(putanja) + "}";
    }

    // ------------------------------------------------------------ skeniranje

    private List<Numera> numere(Path koren) {
        long izmena = najskorijaIzmena(koren);
        Path kes = Alati.podesavanjaFolder().resolve("kes")
                .resolve("lokalno-" + Integer.toHexString(koren.toString().hashCode()) + ".json");

        List<Numera> izKesa = procitajKes(kes, izmena);
        if (izKesa != null) {
            return izKesa;
        }
        List<Numera> nadjene = skeniraj(koren);
        upisiKes(kes, izmena, nadjene);
        return nadjene;
    }

    private List<Numera> skeniraj(Path koren) {
        List<Numera> numere = new ArrayList<>();
        try (Stream<Path> hod = Files.walk(koren)) {
            hod.filter(Files::isRegularFile)
                    .filter(Biblioteka::muzicki)
                    .forEach(f -> numere.add(procitaj(f)));
        } catch (Exception e) {
            log.accept("WARNING: ne mogu da procitam " + koren + " (" + e.getMessage() + ")");
        }
        numere.sort(Comparator.comparing(n -> n.putanja().toString(), String.CASE_INSENSITIVE_ORDER));
        log.accept("Skeniran " + koren + ": " + numere.size() + " pesama");
        return numere;
    }

    private static boolean muzicki(Path f) {
        String ime = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return NASTAVCI.stream().anyMatch(ime::endsWith);
    }

    /**
     * Tag ako ga ima, inace ime fajla. Dosta preuzete muzike nema tagove, a
     * ime fajla je po pravilu "Izvodjac - Naslov".
     */
    private Numera procitaj(Path f) {
        try {
            AudioFile audio = AudioFileIO.read(f.toFile());
            Tag tag = audio.getTag();
            int trajanje = audio.getAudioHeader() == null ? 0 : audio.getAudioHeader().getTrackLength();
            if (tag != null) {
                String izvodjac = polje(tag, FieldKey.ARTIST);
                String naslov = polje(tag, FieldKey.TITLE);
                if (!naslov.isBlank()) {
                    return new Numera(f, izvodjac, naslov, polje(tag, FieldKey.ALBUM), trajanje, true);
                }
            }
            return izImena(f, trajanje);
        } catch (Exception e) {
            return izImena(f, 0);
        }
    }

    private static String polje(Tag tag, FieldKey kljuc) {
        try {
            String v = tag.getFirst(kljuc);
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Numera izImena(Path f, int trajanje) {
        String ime = f.getFileName().toString();
        int tacka = ime.lastIndexOf('.');
        if (tacka > 0) {
            ime = ime.substring(0, tacka);
        }
        int crta = ime.indexOf(" - ");
        if (crta > 0) {
            return new Numera(f, ime.substring(0, crta).trim(), ime.substring(crta + 3).trim(),
                    "", trajanje, false);
        }
        return new Numera(f, "", ime.trim(), "", trajanje, false);
    }

    /** Najskoriji datum izmene bilo kog foldera ispod korena. */
    private static long najskorijaIzmena(Path koren) {
        try (Stream<Path> hod = Files.walk(koren)) {
            return hod.filter(Files::isDirectory).mapToLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (Exception e) {
                    return 0L;
                }
            }).max().orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ kes

    private List<Numera> procitajKes(Path kes, long izmena) {
        if (!Files.isRegularFile(kes)) {
            return null;
        }
        try {
            Object koren = Json.parsiraj(Files.readString(kes, StandardCharsets.UTF_8));
            if ((long) Json.broj(Json.put(koren, "izmena"), -1) != izmena) {
                return null;
            }
            List<Numera> numere = new ArrayList<>();
            for (Object o : Json.lista(Json.put(koren, "numere"))) {
                var m = Json.mapa(o);
                numere.add(new Numera(Path.of(Json.tekst(m.get("putanja"))),
                        Json.tekst(m.get("izvodjac")), Json.tekst(m.get("naslov")),
                        Json.tekst(m.get("album")), (int) Json.broj(m.get("trajanje"), 0),
                        Boolean.TRUE.equals(Json.mapa(o).get("izTaga"))));
            }
            return numere;
        } catch (Exception e) {
            return null;
        }
    }

    private void upisiKes(Path kes, long izmena, List<Numera> numere) {
        StringBuilder sb = new StringBuilder("{\"izmena\":").append(izmena).append(",\"numere\":[\n");
        for (int i = 0; i < numere.size(); i++) {
            Numera n = numere.get(i);
            sb.append("{\"putanja\":").append(Json.navodnici(n.putanja().toString()))
                    .append(",\"izvodjac\":").append(Json.navodnici(n.izvodjac()))
                    .append(",\"naslov\":").append(Json.navodnici(n.naslov()))
                    .append(",\"album\":").append(Json.navodnici(n.album()))
                    .append(",\"trajanje\":").append(n.trajanjeSek())
                    .append(",\"izTaga\":").append(n.izTaga()).append('}')
                    .append(i < numere.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]}\n");
        try {
            Alati.upisiAtomski(kes, sb.toString());
        } catch (Exception e) {
            log.accept("WARNING: kes skena nije upisan (" + e.getMessage() + ")");
        }
    }

    // -------------------------------------------------------- konfiguracija

    private String ucitajKonfiguraciju() {
        if (Files.isRegularFile(korisnicki)) {
            try {
                return Files.readString(korisnicki, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.accept("WARNING: " + FAJL + " se ne cita (" + e.getMessage() + ")");
            }
        }
        try (InputStream ulaz = Biblioteka.class.getResourceAsStream("/its/kvizradio/" + FAJL)) {
            if (ulaz == null) {
                return "{}";
            }
            String telo = new String(ulaz.readAllBytes(), StandardCharsets.UTF_8);
            if (!Files.isRegularFile(korisnicki)) {
                Alati.upisiAtomski(korisnicki, telo);
            }
            return telo;
        } catch (Exception e) {
            return "{}";
        }
    }
}
