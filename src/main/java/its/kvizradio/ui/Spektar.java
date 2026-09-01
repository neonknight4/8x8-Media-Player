package its.kvizradio.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Trake spektra u donjem baru.
 *
 * Brojeve daje player (visine opsega iz VLC-ovog spektra), a ovde se od njih
 * pravi slika koja pristaje ostatku: zlatno na crnom, tanke trake, bez sjaja i
 * boja duge.
 *
 * Tri stvari koje ovo radi nad sirovim brojevima:
 * <ul>
 *   <li><b>rasteze opseg</b> - radijski zvuk je jako kompresovan, pa sve trake
 *       stoje izmedju 0.7 i 0.8. Bez rastezanja se ne bi videlo da se mrdaju;</li>
 *   <li><b>brz uspon, spor pad</b> - inace trake trepere i zamaraju oko;</li>
 *   <li><b>vrh koji polako pada</b> - jedina "ukrasna" linija, kao na starim
 *       analizatorima.</li>
 * </ul>
 */
public final class Spektar extends Canvas {

    private static final Color ZLATNA = Color.web("#D4AF37", 0.72);
    private static final Color VRH = Color.web("#F0D060", 0.5);

    private static final double SIRINA_TRAKE = 4;
    private static final double RAZMAK = 4;

    private final float[] prikaz;
    private final float[] vrhovi;

    /** Klizni opseg sirovih vrednosti - po njemu se rasteze prikaz. */
    private float dno = 1f;
    private float vrh;

    public Spektar(int traka, double visina) {
        super(traka * (SIRINA_TRAKE + RAZMAK) - RAZMAK, visina);
        this.prikaz = new float[traka];
        this.vrhovi = new float[traka];
    }

    /**
     * Jedan kadar; zove se dvadesetak puta u sekundi.
     *
     * Dvadeset opsega koje daje VLC se sazima na onoliko traka koliko ih se
     * crta - manje traka je mirnije za oko nego gust cesalj.
     */
    public void crtaj(float[] sirovi) {
        int traka = prikaz.length;
        float[] opseg = sazmi(sirovi, traka);
        float najmanji = 1f;
        float najveci = 0f;
        for (float v : opseg) {
            najmanji = Math.min(najmanji, v);
            najveci = Math.max(najveci, v);
        }
        // opseg se brzo siri na novu vrednost, a polako vraca - da jedan tih
        // trenutak ne raspamti skalu za sledecih par sekundi
        vrh = Math.max(najveci, vrh - 0.004f);
        dno = Math.min(najmanji, dno + 0.004f);
        float raspon = Math.max(0.06f, vrh - dno);

        for (int i = 0; i < traka; i++) {
            float cilj = Math.max(0f, Math.min(1f, (opseg[i] - dno) / raspon));
            prikaz[i] += (cilj - prikaz[i]) * (cilj > prikaz[i] ? 0.55f : 0.12f);
            vrhovi[i] = Math.max(prikaz[i], vrhovi[i] - 0.012f);
        }
        nacrtaj();
    }

    private static float[] sazmi(float[] sirovi, int traka) {
        float[] izlaz = new float[traka];
        if (sirovi.length == 0) {
            return izlaz;
        }
        for (int i = 0; i < traka; i++) {
            int od = i * sirovi.length / traka;
            int doKraja = Math.max(od + 1, (i + 1) * sirovi.length / traka);
            float zbir = 0;
            for (int j = od; j < doKraja && j < sirovi.length; j++) {
                zbir += sirovi[j];
            }
            izlaz[i] = zbir / (doKraja - od);
        }
        return izlaz;
    }

    /** Sve na nulu - kad muzika stane, trake se spuste i ostanu dole. */
    public void ugasi() {
        java.util.Arrays.fill(prikaz, 0f);
        java.util.Arrays.fill(vrhovi, 0f);
        dno = 1f;
        vrh = 0f;
        nacrtaj();
    }

    private void nacrtaj() {
        GraphicsContext g = getGraphicsContext2D();
        double v = getHeight();
        g.clearRect(0, 0, getWidth(), v);
        for (int i = 0; i < prikaz.length; i++) {
            double x = i * (SIRINA_TRAKE + RAZMAK);
            double visina = Math.max(1, prikaz[i] * v);
            g.setFill(ZLATNA);
            g.fillRect(x, v - visina, SIRINA_TRAKE, visina);
            if (vrhovi[i] > 0.02f) {
                g.setFill(VRH);
                g.fillRect(x, v - Math.max(2, vrhovi[i] * v), SIRINA_TRAKE, 1);
            }
        }
    }
}
