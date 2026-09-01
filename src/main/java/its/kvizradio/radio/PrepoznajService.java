package its.kvizradio.radio;

import its.kvizradio.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Prepoznavanje pesme za stanice koje ne salju naziv u metapodacima.
 *
 * Koristi AudD (https://audd.io): njemu se prosledi URL strima, on sam odatle
 * uzme dvadesetak sekundi zvuka i vrati izvodjaca i naslov - pa aplikacija ne
 * mora nista da snima ni da salje audio.
 *
 * Shazam nema javni API; ono sto kruzi su rekonstruisani klijenti koji krse
 * njihove uslove i pucaju kad se protokol promeni. AudD radi isti posao preko
 * dogovorenog API-ja.
 *
 * Kljuc stoji u kvizradio.properties (prepoznavanje.apiKey) - bez njega dugme
 * javlja gde se uzima, umesto da tiho ne radi.
 */
public final class PrepoznajService {

    /** Nema kljuca ili servis nije odgovorio - poruka ide korisniku. */
    public static class Neuspeh extends Exception {
        public Neuspeh(String poruka) {
            super(poruka);
        }
    }

    private static final String KRAJNJA_TACKA = "https://api.audd.io/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String kljuc;

    public PrepoznajService(String kljuc) {
        this.kljuc = kljuc == null ? "" : kljuc.trim();
    }

    public boolean podesen() {
        return !kljuc.isEmpty();
    }

    /**
     * Blokira dok servis ne odgovori - zvati van JavaFX niti. Prepoznavanje
     * traje dvadesetak sekundi, koliko servisu treba da oslusne strim.
     */
    public Pesma prepoznaj(Stanica stanica) throws Neuspeh {
        if (!podesen()) {
            throw new Neuspeh("Prepoznavanje trazi API kljuc.\n\n"
                    + "Napravi nalog na https://audd.io, pa upisi u kvizradio.properties:\n"
                    + "prepoznavanje.apiKey=tvoj-token");
        }
        String telo = "api_token=" + URLEncoder.encode(kljuc, StandardCharsets.UTF_8)
                + "&url=" + URLEncoder.encode(stanica.url(), StandardCharsets.UTF_8);
        try {
            HttpRequest zahtev = HttpRequest.newBuilder()
                    .uri(URI.create(KRAJNJA_TACKA))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "KvizRadio/1.0")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(telo, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> odgovor = http.send(zahtev,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (odgovor.statusCode() != 200) {
                throw new Neuspeh("Servis za prepoznavanje vratio HTTP " + odgovor.statusCode());
            }
            Object koren = Json.parsiraj(odgovor.body());
            String status = Json.tekst(Json.put(koren, "status"));
            if (!"success".equals(status)) {
                throw new Neuspeh("Servis javlja gresku: " + Json.tekst(Json.put(koren, "error", "error_message")));
            }
            Object rezultat = Json.put(koren, "result");
            if (rezultat == null) {
                throw new Neuspeh("Pesma nije prepoznata. Probaj ponovo za koji trenutak "
                        + "- mozda je bila najava ili reklama.");
            }
            String izvodjac = Json.tekst(Json.mapa(rezultat).get("artist"));
            String naslov = Json.tekst(Json.mapa(rezultat).get("title"));
            if (naslov.isBlank()) {
                throw new Neuspeh("Servis nije vratio naslov pesme.");
            }
            return new Pesma(izvodjac, naslov, Pesma.PREPOZNATO);
        } catch (Neuspeh e) {
            throw e;
        } catch (Exception e) {
            throw new Neuspeh("Prepoznavanje nije uspelo: " + e.getMessage());
        }
    }
}
