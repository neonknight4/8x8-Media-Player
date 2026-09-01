package its.kvizradio;

import its.kvizradio.player.PlayerService;
import its.kvizradio.radio.RadioBrowserService;
import its.kvizradio.radio.Stanica;

import java.util.List;

/**
 * Provera da API i player rade, pre nego sto se oko njih napravi UI.
 *
 * <pre>
 * java -cp target/classes:target/libs/* its.kvizradio.Cli -rs -n 10 -sviraj 20
 *   -rs            samo domace stanice (countrycode=RS)
 *   -tag rock      zanr
 *   -ime "Play"    deo imena stanice
 *   -n 10          koliko stanica u listi
 *   -izbor 3       koju iz liste pustiti (podrazumevano prvu)
 *   -sviraj 20     koliko sekundi svirati (0 = samo lista, bez zvuka)
 *   -zanrovi       ispisi najcesce zanrove umesto stanica
 *   -folder "put"  skeniraj folder sa muzikom i pusti ga (umesto radija)
 *   -redom         folder ide redom umesto nasumicno
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        String tag = vrednost(args, "-tag", null);
        String ime = vrednost(args, "-ime", null);
        String drzava = ima(args, "-rs") ? "RS" : vrednost(args, "-drzava", null);
        int koliko = Integer.parseInt(vrednost(args, "-n", "10"));
        int izbor = Integer.parseInt(vrednost(args, "-izbor", "1"));
        int sekundi = Integer.parseInt(vrednost(args, "-sviraj", "20"));

        if (vrednost(args, "-folder", null) != null) {
            lokalno(vrednost(args, "-folder", null), !ima(args, "-redom"), sekundi);
            return;
        }

        RadioBrowserService api = new RadioBrowserService(System.out::println);

        if (ima(args, "-zanrovi")) {
            for (String z : api.zanrovi(koliko)) {
                System.out.println("  " + z);
            }
            return;
        }

        System.out.println("Trazim: tag=" + tag + " drzava=" + drzava + " ime=" + ime);
        List<Stanica> stanice = api.pretraga(tag, drzava, ime, koliko);
        if (stanice.isEmpty()) {
            System.out.println("Nema rezultata.");
            return;
        }
        for (int i = 0; i < stanice.size(); i++) {
            Stanica s = stanice.get(i);
            System.out.printf("%2d. %-34s %4d kbps  %-6s %5d klikova  %s%n",
                    i + 1, skrati(s.ime(), 34), s.bitrate(), s.kodek(), s.klikovi(), s.url());
        }
        if (sekundi <= 0) {
            return;
        }

        Stanica stanica = stanice.get(Math.min(Math.max(izbor, 1), stanice.size()) - 1);
        System.out.println();
        System.out.println("Pustam: " + stanica.opis() + "  " + stanica.url());

        PlayerService player = new PlayerService(
                st -> System.out.println("  [" + st.stanje() + "] " + st.poruka()),
                System.out::println);
        player.jacina(70);
        player.pusti(stanica);
        api.klik(stanica);

        Thread.sleep(sekundi * 1000L);

        System.out.println("Fade out 2s...");
        player.fadeOut(2000);
        Thread.sleep(2500);
        player.oslobodi();
        System.out.println("Gotovo.");
    }

    /**
     * Provera lokalnog dela: skeniraj folder, ispisi sta je naslo, pa pusti red
     * sviranja - ukljucujuci automatski prelaz na sledecu numeru.
     */
    private static void lokalno(String putanja, boolean nasumicno, int sekundi) throws Exception {
        var biblioteka = new its.kvizradio.lokalno.Biblioteka(System.out::println);
        long pocelo = System.currentTimeMillis();
        var folder = biblioteka.folder("CLI", java.nio.file.Path.of(putanja));
        System.out.println("Skeniranje: " + (System.currentTimeMillis() - pocelo) + "ms, "
                + folder.numere().size() + " pesama");
        for (its.kvizradio.lokalno.Numera n : folder.numere()) {
            System.out.printf("  %-42s %-6s %s%n", skrati(n.opis(), 42), n.trajanje(),
                    n.imaTag() ? "tag" : "iz imena fajla");
        }
        if (folder.numere().isEmpty() || sekundi <= 0) {
            return;
        }

        var red = new its.kvizradio.lokalno.RedSviranja(folder.numere(), nasumicno);
        PlayerService player = new PlayerService(
                st -> System.out.println("  [" + st.stanje() + "]"), System.out::println);
        player.jacina(70);
        player.postaviSlusaocaNapretka(n -> { });
        player.postaviKrajNumere(() -> {
            var sledeca = red.sledeca();
            System.out.println("  kraj numere -> " + (sledeca == null ? "-" : sledeca.opis()));
            if (sledeca != null) {
                player.pustiFajl(sledeca.putanja());
            }
        });

        var prva = red.sledeca();
        System.out.println();
        System.out.println("Pustam (" + (nasumicno ? "nasumicno" : "redom") + "): " + prva.opis());
        player.pustiFajl(prva.putanja());
        for (int i = 0; i < sekundi; i++) {
            Thread.sleep(1000);
        }
        System.out.println("Fade out 2s...");
        player.fadeOut(2000);
        Thread.sleep(2500);
        player.oslobodi();
        System.out.println("Gotovo.");
    }

    private static String vrednost(String[] args, String zastavica, String podrazumevano) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(zastavica)) {
                return args[i + 1];
            }
        }
        return podrazumevano;
    }

    private static boolean ima(String[] args, String zastavica) {
        for (String a : args) {
            if (a.equals(zastavica)) {
                return true;
            }
        }
        return false;
    }

    private static String skrati(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
