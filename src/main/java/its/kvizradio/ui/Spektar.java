package its.kvizradio.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/**
 * Trake spektra u donjem baru.
 *
 * Brojeve daje player (visine opsega iz VLC-ovog spektra), a ovde se od njih
 * pravi slika: tanke trake na crnom, obojene po visini kao na starim
 * analizatorima - plavo dole, pa zeleno, zlatno i crveno u vrhu.
 *
 * Tri stvari koje ovo radi nad sirovim brojevima:
 * <ul>
 *   <li><b>skalira na tipican nivo stanice</b> - otkloni talasa retko idu do
 *       kraja, pa bi se bez toga koristila samo sredina visine;</li>
 *   <li><b>brz uspon, spor pad</b> - inace trake trepere i zamaraju oko;</li>
 *   <li><b>vrh koji polako pada</b> - jedina "ukrasna" linija, kao na starim
 *       analizatorima.</li>
 * </ul>
 */
public final class Spektar extends Canvas {

    /**
     * Boja ide po visini trake, ne po traci - niska traka je plava celom
     * duzinom jer do zelenog dela gradijenta nije ni stigla.
     */
    private static final Stop[] BOJE = {
        new Stop(0.00, Color.web("#4A8CFF")),
        new Stop(0.38, Color.web("#34D399")),
        new Stop(0.72, Color.web("#E3B341")),
        new Stop(1.00, Color.web("#FF6B5A")),
    };

    /** Boja traka kad nista ne svira - jedva vidljiva linija po dnu. */
    private static final Color MIROVANJE = Color.web("#242833");

    private static final double SIRINA_TRAKE = 9;
    private static final double RAZMAK = 4;

    private final LinearGradient bojeTraka;
    private final LinearGradient bojeVrhova;

    private final float[] prikaz;
    private final float[] vrhovi;

    /**
     * Klizni prosek nivoa i prosecno odstupanje od njega (oko dve sekunde
     * pamcenja) - po njima se skalira prikaz.
     */
    private float prosek;
    private float odstupanje;
    /** Koliko je kadrova uslo u skalu od pocetka sviranja; 0 = jos nije postavljena. */
    private int kadrova;

    public Spektar(int traka, double visina) {
        super(traka * (SIRINA_TRAKE + RAZMAK) - RAZMAK, visina);
        this.prikaz = new float[traka];
        this.vrhovi = new float[traka];
        // apsolutne koordinate platna, od dna ka vrhu: tako je boja vezana za
        // visinu u baru, a ne za visinu pojedinacne trake
        this.bojeTraka = gradijent(visina, 0.78);
        this.bojeVrhova = gradijent(visina, 1.0);
    }

    private static LinearGradient gradijent(double visina, double providnost) {
        Stop[] stopovi = new Stop[BOJE.length];
        for (int i = 0; i < BOJE.length; i++) {
            Color c = BOJE[i].getColor();
            stopovi[i] = new Stop(BOJE[i].getOffset(), c.deriveColor(0, 1, 1, providnost));
        }
        return new LinearGradient(0, visina, 0, 0, false, CycleMethod.NO_CYCLE, stopovi);
    }

    /**
     * Jedan kadar; zove se dvadesetak puta u sekundi.
     *
     * Dvadeset opsega koje daje VLC se sazima na onoliko traka koliko ih se
     * crta; kad je traka koliko i opsega, sazimanja nema.
     */
    public void crtaj(float[] sirovi) {
        int traka = prikaz.length;
        float[] opseg = sazmi(sirovi, traka);
        float zbir = 0f;
        for (float v : opseg) {
            zbir += v;
        }
        float sada = zbir / traka;
        // prvi kadrovi posle pocetka su nule - nivoi jos nisu stigli - pa bi
        // skala sela na nulu i sve sto dodje udaralo u plafon dok se ne digne
        if (kadrova == 0 && sada <= 0f) {
            nacrtaj();
            return;
        }
        // na pocetku obican prosek svega do sada, posle klizni: skala je tacna
        // od prvog kadra, a ne tek kad se klizni prosek namesti. Na snimljenim
        // nivoima sa pola sekunde nula na pocetku, udela traka u plafonu u
        // prve dve sekunde: 30-46% -> 1-6%
        kadrova++;
        float brzina = Math.max(0.02f, 1f / kadrova);
        // Skala ide oko onoga sto je za ovu stanicu tipicno, a ne od vrha:
        // pod 1.5 odstupanja ispod proseka, plafon 2.5 iznad. Ranije je pod
        // bio 60% kliznog maksimuma, pa je na dinamicnijim stanicama jedan
        // glasan trenutak odsekao skoro sve - snimljeni nivoi pa pusteni kroz
        // oba nacina, medijana visine trake: RdMix Classic Rock 0.06 -> 0.44,
        // 202 0.10 -> 0.39, LOLA 0.26 -> 0.45. Zajednicko pulsiranje svih
        // traka ostaje, jer se prosek pomera sporo - a ono nosi ritam.
        prosek += (sada - prosek) * brzina;
        float rasipanje = 0f;
        for (float v : opseg) {
            rasipanje += Math.abs(v - prosek);
        }
        odstupanje += (rasipanje / traka - odstupanje) * brzina;
        float pod = prosek - 1.5f * odstupanje;
        float raspon = Math.max(0.05f, 4f * odstupanje);

        for (int i = 0; i < traka; i++) {
            // blago savijanje krive: tiho ide jos nize, glasno ostaje gore
            float udeo = Math.max(0f, Math.min(1f, (opseg[i] - pod) / raspon));
            float cilj = udeo * (float) Math.sqrt(udeo);
            prikaz[i] += (cilj - prikaz[i]) * (cilj > prikaz[i] ? 0.7f : 0.22f);
            vrhovi[i] = Math.max(prikaz[i], vrhovi[i] - 0.016f);
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
        prosek = 0f;
        odstupanje = 0f;
        kadrova = 0;
        nacrtaj();
    }

    private void nacrtaj() {
        GraphicsContext g = getGraphicsContext2D();
        double v = getHeight();
        g.clearRect(0, 0, getWidth(), v);
        for (int i = 0; i < prikaz.length; i++) {
            double x = i * (SIRINA_TRAKE + RAZMAK);
            if (prikaz[i] < 0.02f) {
                // mirno stanje: tanka linija u dnu umesto niza crtica koje
                // izgledaju kao da je nesto ostalo nedocrtano
                g.setFill(MIROVANJE);
                g.fillRect(x, v - 2, SIRINA_TRAKE, 2);
                continue;
            }
            double visina = Math.max(3, prikaz[i] * v);
            g.setFill(bojeTraka);
            g.fillRect(x, v - visina, SIRINA_TRAKE, visina);
            if (vrhovi[i] > 0.02f) {
                g.setFill(bojeVrhova);
                g.fillRect(x, v - Math.max(3, vrhovi[i] * v), SIRINA_TRAKE, 2);
            }
        }
    }
}
