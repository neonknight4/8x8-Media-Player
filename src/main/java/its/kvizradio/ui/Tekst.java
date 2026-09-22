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
}
