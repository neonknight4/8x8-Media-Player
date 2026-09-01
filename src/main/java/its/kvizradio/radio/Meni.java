package its.kvizradio.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Levi meni procitan iz kvizradio.properties. Sve sto se ovde ne nadje, u UI-ju
 * ne postoji - dodavanje grupe je izmena fajla, ne koda.
 */
public final class Meni {

    public record Grupa(String naziv, List<Sekcija> stavke) {
    }

    private Meni() {
    }

    public static List<Grupa> ucitaj(Properties p) {
        List<Grupa> grupe = new ArrayList<>();
        for (String g : reci(p.getProperty("grupe", ""))) {
            List<Sekcija> stavke = new ArrayList<>();
            for (String s : reci(p.getProperty("grupa." + g + ".stavke", ""))) {
                String naziv = p.getProperty("stavka." + s + ".naziv", s);
                String drzava = p.getProperty("stavka." + s + ".drzava", "").trim();
                List<String> tagovi = reci(p.getProperty("stavka." + s + ".tagovi", ""));
                stavke.add(Sekcija.pretraga(naziv, drzava.isEmpty() ? null : drzava, tagovi));
            }
            if (!stavke.isEmpty()) {
                grupe.add(new Grupa(p.getProperty("grupa." + g + ".naziv", g), stavke));
            }
        }
        return grupe;
    }

    /** Vrednosti razdvojene zarezom, bez praznih - isto pravilo kao u HUB-u. */
    public static List<String> reci(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
