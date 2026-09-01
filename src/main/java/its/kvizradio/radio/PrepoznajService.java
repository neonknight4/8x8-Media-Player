package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Prepoznavanje pesme za stanice koje naziv ne salju u metapodacima.
 *
 * Dva servisa, bira se u kvizradio.properties:
 * <ul>
 *   <li><b>acoustid</b> (podrazumevano) - besplatan i otvoren. Otisak zvuka
 *       pravi {@code fpcalc} (Chromaprint) iz dvadeset sekundi strima, a
 *       AcoustID ga trazi u MusicBrainz bazi. Baza je gradjena od celih snimaka,
 *       pa isecak sa radija ne pogadja uvek.</li>
 *   <li><b>audd</b> - placen posle probnog perioda, ali radjen bas za radio:
 *       njemu se posalje URL strima i on sam oslusne.</li>
 * </ul>
 *
 * Shazam nema javni API; ono sto kruzi su rekonstruisani klijenti koji krse
 * njihove uslove i pucaju kad se protokol promeni.
 */
public final class PrepoznajService {

    /** Nema kljuca, nema alata, ili servis nije prepoznao - poruka ide korisniku. */
    public static class Neuspeh extends Exception {
        public Neuspeh(String poruka) {
            super(poruka);
        }
    }

    private static final String ACOUSTID = "https://api.acoustid.org/v2/lookup";
    private static final String AUDD = "https://api.audd.io/";

    /** Koliko sekundi strima ulazi u otisak. Manje od 15 AcoustID cesto ne prepozna. */
    private static final int SEKUNDI_ZA_OTISAK = 20;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Spoljni proces koji upravo osluskuje - da moze da se prekine. */
    private volatile Process spoljni;
    /** Prekid je nas, ne greska - da poruka ne optuzi Python bez razloga. */
    private volatile boolean prekinut;

    private final String servis;
    private final String kljuc;
    private final String python;
    private final Consumer<String> log;

    public PrepoznajService(String servis, String kljuc, String python, Consumer<String> log) {
        this.servis = servis == null || servis.isBlank() ? "audd" : servis.trim().toLowerCase();
        this.kljuc = kljuc == null ? "" : kljuc.trim();
        this.python = python == null || python.isBlank()
                ? (Alati.WINDOWS ? "python" : "python3") : python.trim();
        this.log = log == null ? s -> {} : log;
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

    /** Shazam ne trazi kljuc; ostali ga traze. */
    public boolean podesen() {
        return "shazam".equals(servis) || !kljuc.isEmpty();
    }

    /**
     * Blokira dvadesetak sekundi, koliko traje osluskivanje - zvati van JavaFX
     * niti.
     */
    public Pesma prepoznaj(Stanica stanica) throws Neuspeh {
        if (!podesen()) {
            throw new Neuspeh(bezKljuca());
        }
        prekinut = false;
        try {
            return switch (servis) {
                case "shazam" -> prekoShazama(stanica);
                case "acoustid" -> prekoAcoustId(stanica);
                default -> prekoAudd(stanica);
            };
        } finally {
            spoljni = null;
        }
    }

    private String bezKljuca() {
        if (!"acoustid".equals(servis)) {
            return "Prepoznavanje trazi API kljuc.\n\n"
                    + "Napravi nalog na https://audd.io, pa upisi u kvizradio.properties:\n"
                    + "prepoznavanje.apiKey=tvoj-token";
        }
        return "Prepoznavanje trazi besplatan AcoustID kljuc.\n\n"
                + "1. Otvori https://acoustid.org/new-application i prijavi se\n"
                + "2. Registruj aplikaciju (ime: KvizRadio) i uzmi API key\n"
                + "3. Upisi u kvizradio.properties:\n"
                + "   prepoznavanje.apiKey=tvoj-kljuc";
    }

    // --------------------------------------------------------------- Shazam

    /**
     * shazamio je Python biblioteka, pa ide kao spoljni proces - isto kao sto
     * HUB zove yt-dlp i ffmpeg. Skripta snimi isecak strima i ispise jednu JSON
     * liniju.
     */
    private Pesma prekoShazama(Stanica stanica) throws Neuspeh {
        java.nio.file.Path skripta = skripta();
        try {
            ProcessBuilder pb = new ProcessBuilder(python, skripta.toString(), stanica.url());
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
    private java.nio.file.Path skripta() throws Neuspeh {
        java.nio.file.Path cilj = Alati.podesavanjaFolder().resolve("shazam-prepoznaj.py");
        try (java.io.InputStream ulaz = PrepoznajService.class
                .getResourceAsStream("/its/kvizradio/shazam-prepoznaj.py")) {
            if (ulaz == null) {
                throw new Neuspeh("Nedostaje shazam-prepoznaj.py u aplikaciji.");
            }
            java.nio.file.Files.createDirectories(cilj.getParent());
            java.nio.file.Files.copy(ulaz, cilj,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return cilj;
        } catch (Neuspeh e) {
            throw e;
        } catch (Exception e) {
            throw new Neuspeh("Ne mogu da raspakujem skriptu: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- AcoustID

    private Pesma prekoAcoustId(Stanica stanica) throws Neuspeh {
        String[] otisak = otisak(stanica);
        String telo = "client=" + URLEncoder.encode(kljuc, StandardCharsets.UTF_8)
                + "&meta=recordings"
                + "&duration=" + otisak[0]
                + "&fingerprint=" + URLEncoder.encode(otisak[1], StandardCharsets.UTF_8);
        Object koren = posalji(ACOUSTID, telo);

        if (!"ok".equals(Json.tekst(Json.put(koren, "status")))) {
            throw new Neuspeh("AcoustID javlja: " + Json.tekst(Json.put(koren, "error", "message")));
        }
        for (Object rezultat : Json.lista(Json.put(koren, "results"))) {
            for (Object snimak : Json.lista(Json.mapa(rezultat).get("recordings"))) {
                String naslov = Json.tekst(Json.mapa(snimak).get("title"));
                if (naslov.isBlank()) {
                    continue;
                }
                List<Object> izvodjaci = Json.lista(Json.mapa(snimak).get("artists"));
                String izvodjac = izvodjaci.isEmpty() ? ""
                        : Json.tekst(Json.mapa(izvodjaci.get(0)).get("name"));
                return new Pesma(izvodjac, naslov, Pesma.PREPOZNATO);
            }
        }
        throw new Neuspeh("Pesma nije prepoznata.\n\n"
                + "AcoustID poredi sa bazom celih snimaka, pa isecak sa radija ne pogadja uvek "
                + "- posebno za domacu muziku. Probaj ponovo kroz koji trenutak.");
    }

    /**
     * Otisak zvuka: fpcalc sam otvori strim i oslusne zadati broj sekundi.
     *
     * Zivi strim nema trajanje, pa fpcalc prijavi DURATION=0; AcoustID-u se tada
     * salje duzina isecka, jer je to ono sto je otisak i obuhvatio.
     */
    private String[] otisak(Stanica stanica) throws Neuspeh {
        String fpcalc = Alati.alat("fpcalc");
        if (fpcalc == null) {
            throw new Neuspeh("Nedostaje fpcalc (Chromaprint) uz aplikaciju.\n\n"
                    + "Instaler ga nosi; ako si pokrenuo iz razvojnog okruzenja, instaliraj "
                    + "libchromaprint-tools.");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(fpcalc, "-length",
                    String.valueOf(SEKUNDI_ZA_OTISAK), stanica.url());
            pb.redirectErrorStream(true);
            Process proces = pb.start();
            spoljni = proces;
            String trajanje = "";
            String otisak = "";
            try (BufferedReader citac = new BufferedReader(
                    new InputStreamReader(proces.getInputStream(), StandardCharsets.UTF_8))) {
                String linija;
                while ((linija = citac.readLine()) != null) {
                    if (linija.startsWith("DURATION=")) {
                        trajanje = linija.substring(9).trim();
                    } else if (linija.startsWith("FINGERPRINT=")) {
                        otisak = linija.substring(12).trim();
                    }
                }
            }
            if (!proces.waitFor(SEKUNDI_ZA_OTISAK + 40L, TimeUnit.SECONDS)) {
                proces.destroyForcibly();
                throw new Neuspeh("Osluskivanje strima je trajalo predugo.");
            }
            if (otisak.isEmpty()) {
                throw new Neuspeh("Nije uspelo osluskivanje strima (fpcalc nije vratio otisak).");
            }
            if (trajanje.isEmpty() || "0".equals(trajanje)) {
                trajanje = String.valueOf(SEKUNDI_ZA_OTISAK);
            }
            return new String[]{trajanje, otisak};
        } catch (Neuspeh e) {
            throw e;
        } catch (Exception e) {
            throw new Neuspeh("Osluskivanje nije uspelo: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- AudD

    private Pesma prekoAudd(Stanica stanica) throws Neuspeh {
        String telo = "api_token=" + URLEncoder.encode(kljuc, StandardCharsets.UTF_8)
                + "&url=" + URLEncoder.encode(stanica.url(), StandardCharsets.UTF_8);
        Object koren = posalji(AUDD, telo);

        if (!"success".equals(Json.tekst(Json.put(koren, "status")))) {
            throw new Neuspeh("AudD javlja: " + Json.tekst(Json.put(koren, "error", "error_message")));
        }
        Object rezultat = Json.put(koren, "result");
        if (rezultat == null) {
            throw new Neuspeh("Pesma nije prepoznata. Mozda je bila najava ili reklama.");
        }
        String naslov = Json.tekst(Json.mapa(rezultat).get("title"));
        if (naslov.isBlank()) {
            throw new Neuspeh("Servis nije vratio naslov pesme.");
        }
        return new Pesma(Json.tekst(Json.mapa(rezultat).get("artist")), naslov, Pesma.PREPOZNATO);
    }

    // ----------------------------------------------------------------- mreza

    private static String razlog(String telo, int kod) {
        try {
            String poruka = Json.tekst(Json.put(Json.parsiraj(telo), "error", "message"));
            if (!poruka.isBlank()) {
                return "Servis za prepoznavanje odbio zahtev: " + poruka;
            }
        } catch (Exception ignored) {
            // telo nije JSON - ostaje kod odgovora
        }
        return "Servis za prepoznavanje vratio HTTP " + kod;
    }

    private Object posalji(String adresa, String telo) throws Neuspeh {
        try {
            HttpRequest zahtev = HttpRequest.newBuilder(URI.create(adresa))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "KvizRadio/1.0")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(telo, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> odgovor = http.send(zahtev,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (odgovor.statusCode() != 200) {
                // AcoustID i na gresci vraca JSON sa razlogom ("invalid API key");
                // taj tekst je korisniku upotrebljiviji od golog broja
                throw new Neuspeh(razlog(odgovor.body(), odgovor.statusCode()));
            }
            log.accept("Prepoznavanje: odgovor sa " + adresa);
            return Json.parsiraj(odgovor.body());
        } catch (Neuspeh e) {
            throw e;
        } catch (Exception e) {
            throw new Neuspeh("Servis nije dostupan: " + e.getMessage());
        }
    }
}
