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

    /**
     * Da li je ono sto je stiglo uopste naziv pesme.
     *
     * Deo stanica u to polje gura svoj sajt ili reklamu (Super FM salje
     * "www.superfm.rs"), a to ne treba prikazati kao pesmu.
     */
    public static boolean upotrebljivNaziv(String tekst) {
        if (tekst == null || tekst.isBlank() || tekst.trim().length() < 3) {
            return false;
        }
        String malo = tekst.toLowerCase();
        return !malo.contains("www.") && !malo.contains("http://") && !malo.contains("https://");
    }

    public boolean prazna() {
        return naslov == null || naslov.isBlank();
    }
}
