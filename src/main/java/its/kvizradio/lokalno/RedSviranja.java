package its.kvizradio.lokalno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Red sviranja jednog foldera.
 *
 * Nasumicno znaci nasumicno <b>bez ponavljanja</b>: promesa se ceo spisak i ide
 * se kroz njega, pa se tek na kraju mesa ponovo. Obicno mesanje bi na kviz
 * vece dvaput zaredom pustilo istu pesmu, sto se odmah primeti.
 */
public final class RedSviranja {

    private final List<Numera> numere;
    private final List<Integer> redosled = new ArrayList<>();
    private boolean nasumicno;
    private int mesto = -1;

    public RedSviranja(List<Numera> numere, boolean nasumicno) {
        this.numere = List.copyOf(numere);
        this.nasumicno = nasumicno;
        promesaj();
    }

    public boolean prazan() {
        return numere.isEmpty();
    }

    public int velicina() {
        return numere.size();
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
        return mesto < 0 || mesto >= redosled.size() ? null : numere.get(redosled.get(mesto));
    }

    /** Sledeca; kad se spisak potrosi, mesa se ponovo i krece iz pocetka. */
    public Numera sledeca() {
        if (numere.isEmpty()) {
            return null;
        }
        mesto++;
        if (mesto >= redosled.size()) {
            promesaj();
            mesto = 0;
        }
        return trenutna();
    }

    public Numera prethodna() {
        if (numere.isEmpty()) {
            return null;
        }
        mesto = mesto <= 0 ? redosled.size() - 1 : mesto - 1;
        return trenutna();
    }

    /** Klik na konkretnu pesmu u spisku - red se nastavlja odatle. */
    public Numera postaviNa(Numera numera) {
        int uListi = numere.indexOf(numera);
        if (uListi < 0) {
            return trenutna();
        }
        int uRedosledu = redosled.indexOf(uListi);
        if (uRedosledu >= 0) {
            mesto = uRedosledu;
        }
        return trenutna();
    }

    private void promesaj() {
        redosled.clear();
        for (int i = 0; i < numere.size(); i++) {
            redosled.add(i);
        }
        if (nasumicno) {
            Collections.shuffle(redosled);
        }
        mesto = -1;
    }
}
