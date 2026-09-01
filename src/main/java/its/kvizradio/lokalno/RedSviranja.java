package its.kvizradio.lokalno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Red sviranja jednog foldera.
 *
 * Red je obicna lista onim redom kojim se svira, plus pokazivac na tekucu
 * numeru. Zbog toga premestanje i izbacivanje iz plejliste rade nad istom
 * listom koja se i vidi - nema mapiranja izmedju "spiska" i "redosleda".
 *
 * Nasumicno znaci nasumicno <b>bez ponavljanja</b>: promesa se ceo spisak i ide
 * se kroz njega, pa se tek na kraju mesa ponovo. Obicno mesanje bi na kviz
 * vece dvaput zaredom pustilo istu pesmu, sto se odmah primeti.
 */
public final class RedSviranja {

    private final List<Numera> red = new ArrayList<>();
    /** Redosled iz foldera - da "redom" ima na sta da se vrati posle mesanja. */
    private final List<Numera> izvorni;
    private boolean nasumicno;
    private int mesto = -1;

    public RedSviranja(List<Numera> numere, boolean nasumicno) {
        this.izvorni = List.copyOf(numere);
        this.red.addAll(numere);
        this.nasumicno = nasumicno;
        promesaj();
    }

    public boolean prazan() {
        return red.isEmpty();
    }

    public int velicina() {
        return red.size();
    }

    /** Red onako kako se svira - to je i ono sto plejlista prikazuje. */
    public List<Numera> spisak() {
        return Collections.unmodifiableList(red);
    }

    /** Mesto tekuce numere u spisku; -1 znaci da jos nista nije puslo. */
    public int mesto() {
        return mesto;
    }

    public boolean nasumicno() {
        return nasumicno;
    }

    /** Prebacivanje redom/nasumicno usred veceri; tekuca pesma ostaje tekuca. */
    public void nasumicno(boolean da) {
        if (nasumicno == da) {
            return;
        }
        Numera tekuca = trenutna();
        nasumicno = da;
        promesaj();
        if (tekuca != null) {
            postaviNa(tekuca);
        }
    }

    public Numera trenutna() {
        return mesto < 0 || mesto >= red.size() ? null : red.get(mesto);
    }

    /** Sledeca; kad se spisak potrosi, mesa se ponovo i krece iz pocetka. */
    public Numera sledeca() {
        if (red.isEmpty()) {
            return null;
        }
        mesto++;
        if (mesto >= red.size()) {
            // rucno slozen red se ne dira; mesa se samo kad je nasumicno
            if (nasumicno) {
                Collections.shuffle(red);
            }
            mesto = 0;
        }
        return trenutna();
    }

    public Numera prethodna() {
        if (red.isEmpty()) {
            return null;
        }
        mesto = mesto <= 0 ? red.size() - 1 : mesto - 1;
        return trenutna();
    }

    /** Klik na konkretnu pesmu u spisku - red se nastavlja odatle. */
    public Numera postaviNa(Numera numera) {
        int gde = red.indexOf(numera);
        if (gde >= 0) {
            mesto = gde;
        }
        return trenutna();
    }

    public Numera postaviNa(int index) {
        if (index >= 0 && index < red.size()) {
            mesto = index;
        }
        return trenutna();
    }

    /** Prevlacenje u plejlisti; pokazivac ostaje na istoj numeri, ne na istom broju. */
    public void pomeri(int od, int na) {
        if (od < 0 || od >= red.size() || na < 0 || na >= red.size() || od == na) {
            return;
        }
        Numera tekuca = trenutna();
        red.add(na, red.remove(od));
        if (tekuca != null) {
            mesto = red.indexOf(tekuca);
        }
    }

    /**
     * Izbacivanje iz reda. Ako ode tekuca, pokazivac stane ispred sledece - pa
     * {@code sledeca()} nastavlja tamo gde bi i inace.
     */
    public void ukloni(int index) {
        if (index < 0 || index >= red.size()) {
            return;
        }
        red.remove(index);
        if (index < mesto) {
            mesto--;
        } else if (index == mesto) {
            mesto--;
        }
        if (mesto >= red.size()) {
            mesto = red.size() - 1;
        }
    }

    /** Numere koje su vec u redu se ne dupliraju - dva ista fajla nemaju smisla. */
    public void dodaj(List<Numera> nove) {
        for (Numera n : nove) {
            if (!red.contains(n)) {
                red.add(n);
            }
        }
    }

    /** "Pusti sledecu": odmah iza tekuce, bez diranja ostatka reda. */
    public void ubaciSledecu(Numera n) {
        int stara = red.indexOf(n);
        if (stara >= 0) {
            pomeri(stara, Math.min(mesto + 1, red.size() - 1));
            return;
        }
        red.add(Math.min(mesto + 1, red.size()), n);
    }

    public void isprazni() {
        red.clear();
        mesto = -1;
    }

    /** Mesanje, ili povratak na redosled iz foldera; dodate numere idu na kraj. */
    private void promesaj() {
        if (nasumicno) {
            Collections.shuffle(red);
        } else {
            red.sort(java.util.Comparator.comparingInt(n -> {
                int i = izvorni.indexOf(n);
                return i < 0 ? Integer.MAX_VALUE : i;
            }));
        }
        mesto = -1;
    }
}
