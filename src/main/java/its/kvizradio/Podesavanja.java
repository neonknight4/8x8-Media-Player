package its.kvizradio;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Dva fajla u {@link Alati#podesavanjaFolder()}:
 * <ul>
 *   <li>{@code kvizradio.properties} - levi meni; prvi put se prekopira iz
 *       aplikacije, posle je tvoj i vise se ne dira, pa izmene tagova ne
 *       nestaju pri sledecem pokretanju;</li>
 *   <li>{@code stanje.properties} - jacina i poslednja stanica, pise ih sama
 *       aplikacija pri gasenju.</li>
 * </ul>
 */
public final class Podesavanja {

    private static final String KONFIG = "kvizradio.properties";
    private static final String STANJE = "stanje.properties";

    private Podesavanja() {
    }

    /** Meni: ugradjena konfiguracija, pa preko nje tvoja kopija. */
    public static Properties konfiguracija() {
        Properties p = new Properties();
        try (InputStream in = Podesavanja.class.getResourceAsStream(KONFIG)) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // bez ugradjene konfiguracije meni ostaje prazan; to se vidi odmah
        }

        Path korisnicki = Alati.podesavanjaFolder().resolve(KONFIG);
        if (Files.isRegularFile(korisnicki)) {
            ucitaj(p, korisnicki);
        } else {
            prekopirajZaKorisnika(korisnicki);
        }
        return p;
    }

    public static Properties stanje() {
        Properties p = new Properties();
        Path fajl = Alati.podesavanjaFolder().resolve(STANJE);
        if (Files.isRegularFile(fajl)) {
            ucitaj(p, fajl);
        }
        return p;
    }

    public static void snimiStanje(int jacina, String stanicaJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Jacina i poslednja stanica - pise ih sama aplikacija pri gasenju.\n");
        sb.append("jacina=").append(jacina).append('\n');
        if (stanicaJson != null && !stanicaJson.isBlank()) {
            // jedna linija, escapovan \ da Properties procita nazad isto
            sb.append("stanica=").append(stanicaJson.replace("\\", "\\\\")).append('\n');
        }
        try {
            Alati.upisiAtomski(Alati.podesavanjaFolder().resolve(STANJE), sb.toString());
        } catch (Exception ignored) {
            // udobnost, ne podatak zbog koga se javlja greska
        }
    }

    public static int broj(Properties p, String kljuc, int podrazumevano) {
        try {
            return Integer.parseInt(p.getProperty(kljuc, "").trim());
        } catch (NumberFormatException e) {
            return podrazumevano;
        }
    }

    private static void ucitaj(Properties p, Path fajl) {
        try (var in = Files.newInputStream(fajl)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // ostaje ugradjena konfiguracija
        }
    }

    private static void prekopirajZaKorisnika(Path korisnicki) {
        try (InputStream in = Podesavanja.class.getResourceAsStream(KONFIG)) {
            if (in != null) {
                Files.createDirectories(korisnicki.getParent());
                Files.copy(in, korisnicki);
            }
        } catch (Exception ignored) {
            // radi se i bez korisnicke kopije, samo se tagovi ne mogu menjati
        }
    }
}
