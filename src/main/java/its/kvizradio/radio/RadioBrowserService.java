package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Citanje Radio Browser API-ja (https://api.radio-browser.info).
 *
 * Bez ijedne veze sa UI-jem: svi pozivi su blokirajuci i zovu se iz Task-a ili
 * CompletableFuture-a, nikad sa JavaFX niti.
 *
 * Dve stvari koje API trazi, a lako se previde:
 * <ul>
 *   <li>Nema jednog "pravog" servera - lista mirora se povlaci sa
 *       all.api.radio-browser.info, bira se nasumican, a na gresku se ide na
 *       sledeci. Zato svaki poziv ide kroz {@link #dohvati}.</li>
 *   <li>User-Agent je obavezan i mora da identifikuje aplikaciju; bez njega
 *       server ume da odbije.</li>
 * </ul>
 *
 * Rezultati pretrage se kesiraju 24h u fajl, da ponovno otvaranje iste sekcije
 * ne bombarduje tudji besplatan servis. Kes cuva sirov odgovor, pa je i kad je
 * parser jednog dana pametniji stari fajl i dalje upotrebljiv.
 */
public final class RadioBrowserService {

    private static final String KORISNIK = "KvizRadio/1.0";
    private static final String POPIS_SERVERA = "https://all.api.radio-browser.info/json/servers";
    private static final Duration KES_TRAJE = Duration.ofHours(24);

    /** Kad popis mirora ne moze da se povuce - ovi stoje godinama. */
    private static final List<String> REZERVNI = List.of(
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "at1.api.radio-browser.info");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Consumer<String> log;
    private final Path kesFolder;

    /** Redosled mirora za ovu sesiju; prvi koji radi ostaje na celu. */
    private List<String> serveri;

    public RadioBrowserService(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
        this.kesFolder = Alati.podesavanjaFolder().resolve("kes");
    }

    // ------------------------------------------------------------------ API

    /**
     * Pretraga stanica. Prazan parametar se ne salje.
     *
     * @param tag       zanr, npr. "rock" (API trazi mala slova)
     * @param drzava    ISO kod, npr. "RS"
     * @param ime       deo imena stanice
     * @param koliko    gornja granica broja rezultata
     */
    public List<Stanica> pretraga(String tag, String drzava, String ime, int koliko) {
        StringBuilder u = new StringBuilder("/json/stations/search?hidebroken=true")
                .append("&order=clickcount&reverse=true&limit=").append(koliko);
        dodaj(u, "tag", tag);
        dodaj(u, "countrycode", drzava);
        dodaj(u, "name", ime);

        String telo = kesirano(kljuc("stanice", tag, drzava, ime, String.valueOf(koliko)), u.toString());
        List<Stanica> stanice = new ArrayList<>();
        // isti strim ume da stoji pod dva uuid-a (javni servisi, prijave iz dva
        // izvora) - u mrezi kartica bi to bila ista stanica dvaput
        java.util.Set<String> videniUrl = new java.util.HashSet<>();
        for (Object o : Json.lista(Json.parsiraj(telo == null ? "[]" : telo))) {
            Stanica s = Stanica.iz(o);
            if (s.upotrebljiva() && videniUrl.add(s.url())) {
                stanice.add(s);
            }
        }
        return stanice;
    }

    /**
     * Stavka menija u listu stanica. Tagovi su ILI, a API zna samo jedan tag po
     * upitu, pa se pretrage spajaju: ista stanica iz dve pretrage broji se
     * jednom, a duplikati po URL-u (isti strim pod dva uuid-a, cesto kod
     * javnih servisa) se izbacuju da mreza kartica ne bude ista stvar dvaput.
     */
    public List<Stanica> pretraga(Sekcija sekcija, int koliko) {
        if (sekcija.tagovi().isEmpty()) {
            return pretraga(null, sekcija.drzava(), null, koliko);
        }
        List<Stanica> spojene = new ArrayList<>();
        java.util.Set<String> videni = new java.util.HashSet<>();
        for (String tag : sekcija.tagovi()) {
            for (Stanica s : pretraga(tag, sekcija.drzava(), null, koliko)) {
                if (videni.add(s.uuid()) && videni.add(s.url())) {
                    spojene.add(s);
                }
            }
        }
        spojene.sort((a, b) -> Integer.compare(b.klikovi(), a.klikovi()));
        return spojene.size() > koliko ? new ArrayList<>(spojene.subList(0, koliko)) : spojene;
    }

    /**
     * Mreze bez reklama, grupisane po mrezi.
     *
     * API ne zna sta je "bez reklama", pa se do njih dolazi pretragom po imenu
     * mreze - a takva pretraga vraca i tudje stanice sa slicnim imenom, zato se
     * u odeljak mreze uzima samo ono cije ime stvarno sadrzi trazeni pojam.
     *
     * @param samoMreza naziv jedne mreze, ili null za sve
     */
    public List<Odeljak> bezReklama(BezReklama konfiguracija, String samoMreza, int koliko) {
        List<Odeljak> odeljci = new ArrayList<>();
        java.util.Set<String> videni = new java.util.HashSet<>();

        for (BezReklama.Mreza mreza : konfiguracija.mreze()) {
            if (samoMreza != null && !samoMreza.equals(mreza.naziv())) {
                continue;
            }
            List<Stanica> nadjene = new ArrayList<>();
            for (String upit : mreza.upiti()) {
                for (Stanica s : pretraga(null, null, upit, koliko)) {
                    boolean pravaMreza = s.ime().toLowerCase().contains(upit.toLowerCase());
                    if (pravaMreza && videni.add(s.uuid()) && videni.add(s.url())) {
                        nadjene.add(s);
                    }
                }
            }
            nadjene.sort((a, b) -> a.ime().compareToIgnoreCase(b.ime()));
            if (!nadjene.isEmpty()) {
                odeljci.add(new Odeljak(mreza.naziv(), nadjene));
            }
        }

        if (samoMreza == null) {
            List<Stanica> ostale = new ArrayList<>();
            for (String tag : konfiguracija.tagovi()) {
                for (Stanica s : pretraga(tag, null, null, koliko)) {
                    if (videni.add(s.uuid()) && videni.add(s.url())) {
                        ostale.add(s);
                    }
                }
            }
            ostale.sort((a, b) -> Integer.compare(b.klikovi(), a.klikovi()));
            if (!ostale.isEmpty()) {
                odeljci.add(new Odeljak("Ostale bez reklama", ostale));
            }
        }
        return odeljci;
    }

    /** Najzastupljeniji zanrovi, za sidebar. */
    public List<String> zanrovi(int koliko) {
        String telo = kesirano(kljuc("zanrovi", String.valueOf(koliko)),
                "/json/tags?order=stationcount&reverse=true&limit=" + koliko);
        List<String> imena = new ArrayList<>();
        for (Object o : Json.lista(Json.parsiraj(telo == null ? "[]" : telo))) {
            String ime = Json.tekst(Json.mapa(o).get("name"));
            if (!ime.isBlank()) {
                imena.add(ime);
            }
        }
        return imena;
    }

    /**
     * Brojac klikova - API tako zna koja je stanica ziva i popularna, a mi po
     * tome sortiramo pretragu. Ako ne prodje, nista se ne desava: to nije
     * podatak zbog koga se pustanje muzike prekida.
     */
    public void klik(Stanica stanica) {
        try {
            dohvati("/json/url/" + stanica.uuid());
        } catch (Exception e) {
            log.accept("WARNING: brojac klikova nije prosao (" + e.getMessage() + ")");
        }
    }

    // ---------------------------------------------------------------- mreza

    /**
     * Poziv na prvi mirror koji odgovori. Server koji je pukao ide na kraj
     * liste, pa se sledeci put ne cekaju isti timeout-i.
     */
    private String dohvati(String putanja) throws IOException, InterruptedException {
        List<String> lista = new ArrayList<>(serveri());
        IOException poslednja = null;

        for (String server : lista) {
            try {
                HttpRequest zahtev = HttpRequest.newBuilder()
                        .uri(URI.create("https://" + server + putanja))
                        .header("User-Agent", KORISNIK)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> odgovor = http.send(zahtev, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (odgovor.statusCode() == 200) {
                    return odgovor.body();
                }
                poslednja = new IOException("HTTP " + odgovor.statusCode() + " sa " + server);
            } catch (IOException e) {
                poslednja = e;
            }
            log.accept("WARNING: " + server + " ne odgovara, idem na sledeci mirror");
            serveri.remove(server);
            serveri.add(server);
        }
        throw poslednja == null ? new IOException("nema nijednog mirrora") : poslednja;
    }

    /** Popis mirora, jednom po sesiji, promesan da se opterecenje raspodeli. */
    private synchronized List<String> serveri() {
        if (serveri != null) {
            return serveri;
        }
        List<String> nadjeni = new ArrayList<>();
        try {
            HttpRequest zahtev = HttpRequest.newBuilder()
                    .uri(URI.create(POPIS_SERVERA))
                    .header("User-Agent", KORISNIK)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> odgovor = http.send(zahtev, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (odgovor.statusCode() == 200) {
                for (Object o : Json.lista(Json.parsiraj(odgovor.body()))) {
                    String ime = Json.tekst(Json.mapa(o).get("name"));
                    if (!ime.isBlank() && !nadjeni.contains(ime)) {
                        nadjeni.add(ime);
                    }
                }
            }
        } catch (Exception e) {
            log.accept("WARNING: popis mirora nije stigao (" + e.getMessage() + "), idu rezervni");
        }
        Collections.shuffle(nadjeni);
        // rezervni idu na kraj i kad je popis stigao: popis danas ume da vrati
        // samo jedan mirror, pa bez ovoga failover nema gde da ode
        for (String rezervni : REZERVNI) {
            if (!nadjeni.contains(rezervni)) {
                nadjeni.add(rezervni);
            }
        }
        serveri = nadjeni;
        log.accept("Radio Browser mirror: " + serveri.get(0) + " (od " + serveri.size() + ")");
        return serveri;
    }

    // ------------------------------------------------------------------ kes

    /**
     * Odgovor iz kesa ako nije stariji od 24h, inace sa mreze.
     *
     * Ako mreza padne, a kes postoji makar i star - vraca se stari. Bolje
     * lista od pre nedelju dana nego prazan ekran usred kviza.
     */
    private String kesirano(String kljuc, String putanja) {
        Path fajl = kesFolder.resolve(kljuc + ".json");
        try {
            if (Files.isRegularFile(fajl)) {
                Instant pisan = Files.getLastModifiedTime(fajl).toInstant();
                if (pisan.plus(KES_TRAJE).isAfter(Instant.now())) {
                    return Files.readString(fajl, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.accept("WARNING: kes se ne cita (" + e.getMessage() + ")");
        }

        try {
            String telo = dohvati(putanja);
            try {
                Alati.upisiAtomski(fajl, telo);
            } catch (IOException e) {
                log.accept("WARNING: kes se ne upisuje (" + e.getMessage() + ")");
            }
            return telo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            log.accept("ERROR: Radio Browser nedostupan (" + e.getMessage() + ")");
            try {
                if (Files.isRegularFile(fajl)) {
                    log.accept("Koristim stari kes od " + Files.getLastModifiedTime(fajl));
                    return Files.readString(fajl, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // ako ni kes ne moze da se procita, ostaje prazan rezultat
            }
            return null;
        }
    }

    /** Ime fajla u kesu - citljivo koliko moze, jedinstveno koliko mora. */
    private static String kljuc(String... delovi) {
        String spojeno = String.join("-", java.util.Arrays.stream(delovi)
                .map(d -> d == null ? "" : d).toList());
        String cisto = spojeno.toLowerCase().replaceAll("[^a-z0-9-]+", "_");
        if (cisto.length() > 60) {
            cisto = cisto.substring(0, 60);
        }
        return cisto + "-" + Integer.toHexString(spojeno.hashCode());
    }

    private static void dodaj(StringBuilder u, String parametar, String vrednost) {
        if (vrednost != null && !vrednost.isBlank()) {
            u.append('&').append(parametar).append('=')
                    .append(URLEncoder.encode(vrednost.trim(), StandardCharsets.UTF_8));
        }
    }
}
