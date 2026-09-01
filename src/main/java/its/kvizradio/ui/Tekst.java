package its.kvizradio.ui;

/**
 * Sitnice oko teksta koje trazi dizajn.
 *
 * JavaFX CSS nema letter-spacing, a dizajn ga na malim verzalnim natpisima ima
 * svuda (0.2em i vise). Razmak se zato ubacuje u sam tekst - isti postupak kao
 * u naslovu HUB-a ("P A B  K V I Z").
 */
public final class Tekst {

    private Tekst() {
    }

    /** Verzal sa razmakom izmedju slova - za oznake tipa VOL, PLAYING, RUNDA. */
    public static String razmaknuto(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(' '); // tanki razmak
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** Prva slova prve dve reci, kao rezerva kad favicon ne stigne. */
    public static String inicijali(String ime) {
        String[] reci = ime.replaceAll("[^\\p{L}\\p{N} -]", "").trim().split("[\\s-]+");
        StringBuilder sb = new StringBuilder();
        for (String r : reci) {
            if (!r.isBlank() && sb.length() < 2) {
                sb.append(Character.toUpperCase(r.charAt(0)));
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
