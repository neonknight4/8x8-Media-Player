package its.kvizradio.radio;

/**
 * Pesma koja trenutno ide.
 *
 * Dolazi iz dva izvora: iz metapodataka strima (ICY, polje NOW_PLAYING) ili iz
 * prepoznavanja zvuka. Mereno na 14 stanica, naziv salje njih pet - zato oba.
 */
public record Pesma(String izvodjac, String naslov, String izvor) {

    public static final String IZ_STRIMA = "strim";
    public static final String PREPOZNATO = "prepoznato";

    /** "Izvodjac - Naslov" je nepisano pravilo u ICY metapodacima. */
    public static Pesma izNaziva(String tekst, String izvor) {
        String cist = tekst == null ? "" : tekst.trim();
        int crta = cist.indexOf(" - ");
        if (crta > 0) {
            return new Pesma(cist.substring(0, crta).trim(), cist.substring(crta + 3).trim(), izvor);
        }
        return new Pesma("", cist, izvor);
    }

    public boolean prazna() {
        return naslov == null || naslov.isBlank();
    }
}
