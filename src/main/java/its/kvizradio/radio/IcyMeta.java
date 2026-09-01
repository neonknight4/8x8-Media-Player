package its.kvizradio.radio;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Naziv pesme iz ICY metapodataka, procitan direktno sa strima.
 *
 * VLC ne izlozi ovaj podatak za dosta strimova (mereno: vlcj ga daje za pet
 * stanica od cetrnaest, a direktno citanje za sedam od trinaest - ukljucujuci
 * SomaFM i SWR3 gde vlcj vraca null). Zato se cita ovde, sopstvenim zahtevom.
 *
 * Kako radi: uz zahtev ide zaglavlje {@code Icy-MetaData: 1}, server odgovori
 * zaglavljem {@code icy-metaint} - na svakih toliko bajtova zvuka ubaci blok sa
 * {@code StreamTitle='...'}. Procita se prvi takav blok i veza se zatvara: ne
 * drzi se druga veza otvorena celo vece, jer manje stanice imaju ogranicen broj
 * slusalaca a svaka veza troši jedno mesto.
 */
public final class IcyMeta {

    /** Koliko blokova cekamo na naslov pre nego sto odustanemo. */
    private static final int NAJVISE_BLOKOVA = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Blokira dok ne procita naslov ili ne odustane - zvati van JavaFX niti.
     *
     * @return pesma, ili null ako strim ne salje naziv
     */
    public Pesma procitaj(Stanica stanica) {
        try {
            HttpRequest zahtev = HttpRequest.newBuilder(URI.create(stanica.url()))
                    .header("Icy-MetaData", "1")
                    .header("User-Agent", "KvizRadio/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<InputStream> odgovor = http.send(zahtev, HttpResponse.BodyHandlers.ofInputStream());
            String razmakZaglavlje = odgovor.headers().firstValue("icy-metaint").orElse(null);
            if (razmakZaglavlje == null) {
                // HLS (.m3u8) i deo Shoutcast servera ovo ne salju
                odgovor.body().close();
                return null;
            }
            int razmak = Integer.parseInt(razmakZaglavlje.trim());
            try (InputStream ulaz = odgovor.body()) {
                for (int blok = 0; blok < NAJVISE_BLOKOVA; blok++) {
                    if (!preskoci(ulaz, razmak)) {
                        return null;
                    }
                    String naslov = procitajBlok(ulaz);
                    if (naslov != null && !naslov.isBlank()) {
                        return Pesma.izNaziva(naslov, Pesma.IZ_STRIMA);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Zvuk izmedju dva metapodatka se preskace - njega svira vlcj, ne mi. */
    private static boolean preskoci(InputStream ulaz, int koliko) throws java.io.IOException {
        long preskoceno = 0;
        while (preskoceno < koliko) {
            long n = ulaz.skip(koliko - preskoceno);
            if (n <= 0) {
                if (ulaz.read() < 0) {
                    return false;
                }
                n = 1;
            }
            preskoceno += n;
        }
        return true;
    }

    /** Duzina bloka je jedan bajt, u jedinicama od 16 bajtova. */
    private static String procitajBlok(InputStream ulaz) throws java.io.IOException {
        int duzina = ulaz.read();
        if (duzina <= 0) {
            return null;
        }
        byte[] bafer = new byte[duzina * 16];
        int procitano = 0;
        while (procitano < bafer.length) {
            int n = ulaz.read(bafer, procitano, bafer.length - procitano);
            if (n < 0) {
                break;
            }
            procitano += n;
        }
        String blok = new String(bafer, 0, procitano, StandardCharsets.UTF_8);
        int od = blok.indexOf("StreamTitle='");
        if (od < 0) {
            return null;
        }
        int doKraja = blok.indexOf("';", od);
        return blok.substring(od + 13, doKraja < 0 ? blok.length() : doKraja).trim();
    }
}
