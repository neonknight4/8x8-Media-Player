package its.kvizradio.radio;

import its.kvizradio.Json;

import java.util.Map;

/**
 * Jedna stanica iz Radio Browser-a, samo ona polja koja aplikacija koristi.
 *
 * Za pustanje ide {@code url}, a to je API-jevo {@code url_resolved}: polje
 * {@code url} zna da bude .pls/.m3u playlista ili preusmerenje, a
 * {@code url_resolved} je stvarni strim koji je server vec razresio.
 */
public record Stanica(
        String uuid,
        String ime,
        String url,
        String favicon,
        String tagovi,
        String drzava,
        String kodek,
        int bitrate,
        int klikovi) {

    public static Stanica iz(Object json) {
        Map<String, Object> m = Json.mapa(json);
        String url = Json.tekst(m.get("url_resolved"));
        return new Stanica(
                Json.tekst(m.get("stationuuid")),
                Json.tekst(m.get("name")).trim(),
                url.isBlank() ? Json.tekst(m.get("url")) : url,
                Json.tekst(m.get("favicon")),
                Json.tekst(m.get("tags")),
                Json.tekst(m.get("countrycode")),
                Json.tekst(m.get("codec")),
                (int) Json.broj(m.get("bitrate"), 0),
                (int) Json.broj(m.get("clickcount"), 0));
    }

    /** Zapis za omiljene - ista polja i ista imena kao u API-ju, pa {@link #iz} cita i jedno i drugo. */
    public String uJson() {
        return "{"
                + "\"stationuuid\":" + Json.navodnici(uuid)
                + ",\"name\":" + Json.navodnici(ime)
                + ",\"url_resolved\":" + Json.navodnici(url)
                + ",\"favicon\":" + Json.navodnici(favicon)
                + ",\"tags\":" + Json.navodnici(tagovi)
                + ",\"countrycode\":" + Json.navodnici(drzava)
                + ",\"codec\":" + Json.navodnici(kodek)
                + ",\"bitrate\":" + bitrate
                + ",\"clickcount\":" + klikovi
                + "}";
    }

    /** Ime + bitrate, onako kako stoji na kartici i u donjem baru. */
    public String opis() {
        return bitrate > 0 ? ime + " (" + bitrate + " kbps)" : ime;
    }

    public boolean upotrebljiva() {
        return !uuid.isBlank() && url.startsWith("http");
    }
}
