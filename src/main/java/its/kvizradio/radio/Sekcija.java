package its.kvizradio.radio;

import java.util.List;

/**
 * Jedna stavka levog menija: naziv koji vidi voditelj i sta se po njoj trazi.
 *
 * Tagovi su lista ILI - Radio Browser ume samo jedan tag po upitu, a "folk" i
 * "narodna" su ista stvar, pa se pretrage spoje.
 */
public record Sekcija(String naziv, String drzava, List<String> tagovi, Vrsta vrsta) {

    public enum Vrsta {
        PRETRAGA, OMILJENE, BEZ_REKLAMA
    }

    public static Sekcija pretraga(String naziv, String drzava, List<String> tagovi) {
        return new Sekcija(naziv, drzava, tagovi, Vrsta.PRETRAGA);
    }

    /**
     * Jedna mreza bez reklama, ili sve njih zajedno kad je {@code mreza} null.
     * Naziv mreze stoji u {@code drzava} polju samo kao nosac - pretraga za ovu
     * vrstu ne ide kroz drzavu nego kroz mreze.json.
     */
    public static Sekcija bezReklama(String naziv, String mreza) {
        return new Sekcija(naziv, mreza, List.of(), Vrsta.BEZ_REKLAMA);
    }

    public static Sekcija omiljene() {
        return new Sekcija("Omiljene", null, List.of(), Vrsta.OMILJENE);
    }
}
