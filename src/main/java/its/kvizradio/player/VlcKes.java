package its.kvizradio.player;

import its.kvizradio.Alati;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Kes plugina (plugins\plugins.dat) za VLC koji instalacija nosi sa sobom.
 *
 * ZASTO: VLC-ov win64 zip nema plugins.dat - pravi ga tek VLC-ov instaler. Bez
 * njega libvlc pri SVAKOM pokretanju ucitava svih ~360 plugin DLL-ova da bi
 * saznao sta koji ume, a Defender skenira svaki. To je bilo sporo pokretanje
 * KvizRadija. Sam libvlc kes ne upisuje - to radi samo vlc-cache-gen.exe.
 *
 * Kes pamti velicinu i vreme izmene svakog plugina. Posle instalacije ta vremena
 * ne moraju da budu ona iz build-a, a posle update-a su sigurno druga - tada
 * libvlc tiho ucitava sve kao da kesa nema. Zato se kes pravi i ovde, jednom po
 * verziji, u pozadini: prvo pokretanje posle instalacije je jos sporo, svako
 * sledece nije.
 *
 * Na Linuxu se koristi sistemski VLC, a njegov kes pravi paket - tamo se nista
 * ne radi.
 */
public final class VlcKes {

    private static final String OZNAKA = "plugins.dat.verzija";

    private VlcKes() {
    }

    /** Pravi kes u pozadini ako ga nema ili je od druge verzije; odmah se vraca. */
    public static void osveziUPozadini(Consumer<String> log) {
        Path vlc = Alati.nadjiFolder("vlc");
        if (vlc == null) {
            return;
        }
        Path generator = vlc.resolve("vlc-cache-gen.exe");
        Path plugini = vlc.resolve("plugins");
        if (!Files.isRegularFile(generator) || !Files.isDirectory(plugini)) {
            return;
        }

        String verzija = Alati.verzija();
        Path kes = plugini.resolve("plugins.dat");
        Path oznaka = plugini.resolve(OZNAKA);
        if (Files.isRegularFile(kes) && verzija.equals(procitaj(oznaka))) {
            return;
        }

        Thread nit = new Thread(() -> napravi(generator, plugini, kes, oznaka, verzija, log),
                "vlc-kes-plugina");
        nit.setDaemon(true);
        nit.start();
    }

    private static void napravi(Path generator, Path plugini, Path kes, Path oznaka,
            String verzija, Consumer<String> log) {

        long pocetak = System.currentTimeMillis();
        try {
            Process p = new ProcessBuilder(generator.toString(), plugini.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(3, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                log.accept("WARNING: vlc-cache-gen nije zavrsio za 3 min - kes plugina nije napravljen");
                return;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(kes)) {
                log.accept("WARNING: vlc-cache-gen izasao sa " + p.exitValue()
                        + " - kes plugina nije napravljen, pokretanje ostaje sporo");
                return;
            }
            Files.writeString(oznaka, verzija, StandardCharsets.UTF_8);
            log.accept("Kes VLC plugina napravljen za " + (System.currentTimeMillis() - pocetak)
                    + " ms - sledece pokretanje je brze");
        } catch (Exception e) {
            // Program Files nije upisiv (ako je instalacija izabrala taj folder):
            // radi se i bez kesa, samo sporije.
            log.accept("WARNING: kes VLC plugina nije napravljen (" + e.getMessage() + ")");
        }
    }

    private static String procitaj(Path fajl) {
        try {
            return Files.readString(fajl, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
