package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Prepoznavanje pesme - za stanice koje naziv ne salju u metapodacima, i za
 * lokalne fajlove bez uredjenih tagova.
 *
 * Ide preko biblioteke shazamio, u zasebnom Python procesu. Probani su i AudD
 * (placen) i AcoustID (besplatan, ali indeksira otiske celih snimaka pa isecak
 * sa radija nema sta da pogodi) - oba su izbacena, ostao je samo Shazam, koji
 * radi i za strim i za fajl sa diska.
 *
 * Shazam nema javni API; shazamio je rekonstruisan klijent koji krsi njihove
 * uslove i puca kad se protokol promeni. Za licnu upotrebu, ne za deljenje.
 */
public final class PrepoznajService {

    /** Nema alata, ili servis nije prepoznao - poruka ide korisniku. */
    public static class Neuspeh extends Exception {
        public Neuspeh(String poruka) {
            super(poruka);
        }
    }

    /** Spoljni proces koji upravo osluskuje - da moze da se prekine. */
    private volatile Process spoljni;
    /** Prekid je nas, ne greska - da poruka ne optuzi Python bez razloga. */
    private volatile boolean prekinut;

    private final String python;
    private final Consumer<String> log;

    public PrepoznajService(String python, Consumer<String> log) {
        this.python = python == null || python.isBlank()
                ? podrazumevaniPython() : python.trim();
        this.log = log == null ? s -> {} : log;
    }

    /**
     * Windows instaler nosi svoj Python sa shazamio-om u podfolderu "python",
     * kao sto nosi i VLC - na tudjem laptopu u kafani se ne racuna na to da je
     * Python instaliran. Ako ga nema (razvoj, Linux), ide golo ime iz PATH-a.
     */
    private static String podrazumevaniPython() {
        Path folder = Alati.nadjiFolder("python");
        if (folder != null) {
            Path exe = folder.resolve(Alati.WINDOWS ? "python.exe" : "bin/python");
            if (Files.isRegularFile(exe)) {
                return exe.toString();
            }
        }
        return Alati.WINDOWS ? "python" : "python3";
    }

    /**
     * Prekida osluskivanje u toku.
     *
     * Zove se kad voditelj promeni stanicu: rezultat bi se odnosio na ono sto
     * se vise ne cuje. Bez ovoga bi python i ffmpeg jos desetak sekundi drzali
     * vezu ka staroj stanici.
     */
    public void prekini() {
        prekinut = true;
        Process p = spoljni;
        if (p != null) {
            // ffmpeg je dete Python procesa: bez ovoga ostane da vuce strim
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /**
     * Blokira dvadesetak sekundi, koliko traje osluskivanje - zvati van JavaFX
     * niti.
     */
    public Pesma prepoznaj(Stanica stanica) throws Neuspeh {
        return pokreni(stanica.url());
    }

    /**
     * Prepoznavanje fajla sa diska - za lokalne pesme bez tagova.
     *
     * Skripta prepoznaje isecak isto kao kod strima; razlika je samo u tome sto
     * ffmpeg cita fajl umesto mreze.
     */
    public Pesma prepoznajFajl(Path fajl) throws Neuspeh {
        return pokreni(fajl.toString());
    }

    private Pesma pokreni(String izvor) throws Neuspeh {
        prekinut = false;
        try {
            return prekoShazama(izvor);
        } finally {
            spoljni = null;
        }
    }

    // --------------------------------------------------------------- Shazam

    /**
     * shazamio je Python biblioteka, pa ide kao spoljni proces - isto kao sto
     * HUB zove yt-dlp i ffmpeg. Skripta snimi isecak i ispise jednu JSON liniju.
     */
    private Pesma prekoShazama(String izvor) throws Neuspeh {
        Path skripta = skripta();
        try {
            ProcessBuilder pb = new ProcessBuilder(python, skripta.toString(), izvor);
            String ffmpeg = Alati.alat("ffmpeg");
            if (ffmpeg != null) {
                pb.environment().put("FFMPEG", ffmpeg);
            }
            Process proces = pb.start();
            spoljni = proces;
            String izlaz = "";
            try (BufferedReader citac = new BufferedReader(
                    new InputStreamReader(proces.getInputStream(), StandardCharsets.UTF_8))) {
                String linija;
                while ((linija = citac.readLine()) != null) {
                    if (linija.startsWith("{")) {
                        izlaz = linija;
                    }
                }
            }
            if (!proces.waitFor(120, TimeUnit.SECONDS)) {
                proces.destroyForcibly();
                throw new Neuspeh("Prepoznavanje je trajalo predugo.");
            }
            if (izlaz.isEmpty()) {
                String greske = new String(proces.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new Neuspeh("shazamio nije odgovorio.\n\n"
                        + (greske.isBlank() ? "Proveri prepoznavanje.python u konfiguraciji."
                                : greske.trim()));
            }
            Object koren = Json.parsiraj(izlaz);
            String greska = Json.tekst(Json.mapa(koren).get("greska"));
            if (!greska.isBlank()) {
                throw new Neuspeh(greska);
            }
            String naslov = Json.tekst(Json.mapa(koren).get("naslov"));
            if (naslov.isBlank()) {
                throw new Neuspeh("Pesma nije prepoznata.");
            }
            log.accept("Prepoznavanje: shazamio odgovorio");
            return new Pesma(Json.tekst(Json.mapa(koren).get("izvodjac")), naslov, Pesma.PREPOZNATO);
        } catch (Neuspeh e) {
            throw e;
        } catch (java.io.IOException e) {
            if (prekinut) {
                throw new Neuspeh("Prepoznavanje prekinuto.");
            }
            throw new Neuspeh("Ne mogu da pokrenem Python (" + python + ").\n\n"
                    + "Treba Python 3 sa shazamio i ffmpeg:\n"
                    + "  python3 -m venv venv && venv/bin/pip install shazamio\n"
                    + "pa u kvizradio.properties:\n"
                    + "  prepoznavanje.python=putanja/do/venv/bin/python");
        } catch (Exception e) {
            if (prekinut) {
                throw new Neuspeh("Prepoznavanje prekinuto.");
            }
            throw new Neuspeh("Prepoznavanje nije uspelo: " + e.getMessage());
        }
    }

    /**
     * Skripta se raspakuje iz aplikacije pri svakoj upotrebi - tako je uvek ona
     * koju nosi tekuca verzija, a ne zaostala kopija.
     */
    private Path skripta() throws Neuspeh {
        Path cilj = Alati.podesavanjaFolder().resolve("shazam-prepoznaj.py");
        try (java.io.InputStream ulaz = PrepoznajService.class
                .getResourceAsStream("/its/kvizradio/shazam-prepoznaj.py")) {
            if (ulaz == null) {
                throw new Neuspeh("Nedostaje shazam-prepoznaj.py u aplikaciji.");
            }
            Files.createDirectories(cilj.getParent());
            Files.copy(ulaz, cilj, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return cilj;
        } catch (Neuspeh e) {
            throw e;
        } catch (Exception e) {
            throw new Neuspeh("Ne mogu da raspakujem skriptu: " + e.getMessage());
        }
    }
}
