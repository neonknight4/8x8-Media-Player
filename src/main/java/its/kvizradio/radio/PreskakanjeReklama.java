package its.kvizradio.radio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kad na stanici krenu reklame, prelazi se na sledecu iz istog spiska odakle
 * je pustena - zanr, domace, omiljene (u omiljenima ista grupa). Ukljucuje se
 * dugmetom; bez njega se nista ne menja.
 *
 * Reklama se ne cuje, nego se zakljucuje iz toga da pesma ne ide: stanica
 * koja u ICY-u salje nazive pesama posalje prazan naslov, svoje ime, sajt ili
 * tekst bez izvodjaca, i tako ostane bar {@link #ICY_BEZ_PESME_MS} - dzingl
 * izmedju dve pesme je kraci. Isto se vidi i kad ide prica ili vesti, pa se i
 * one preskacu.
 *
 * Stanice bez naziva se ne preskacu. Probano je i sa Shazamom (dva promasaja
 * zaredom = reklama): uzivo je za 13 minuta preskocio tri stanice, svaki put
 * na kraju pesme - dzingl, voditelj ili domaca pesma koju Shazam ne zna
 * izgledaju isto kao reklama, a reklamu sa poznatom podlogom ume da
 * "prepozna".
 *
 * Nema JavaFX-a: ovde je samo odluka, prebacivanje radi aplikacija.
 */
public final class PreskakanjeReklama {

    /** Koliko dugo ICY sme da javlja "nije pesma" pre nego sto se proglasi reklamom. */
    static final long ICY_BEZ_PESME_MS = 30_000;
    /** Preskocena stanica se toliko ne bira ponovo - reklamni blok traje par minuta. */
    static final long ODMOR_MS = 5 * 60_000;

    private boolean ukljuceno;
    private List<Stanica> spisak = List.of();
    /** Kad je koja stanica napustena zbog reklama, po uuid-u. */
    private final Map<String, Long> preskocene = new HashMap<>();

    public boolean ukljuceno() {
        return ukljuceno;
    }

    public void ukljuci(boolean da) {
        ukljuceno = da;
    }

    /** Voditelj je pustio stanicu iz ovog spiska - iz njega se bira sledeca. */
    public void postaviSpisak(List<Stanica> stanice) {
        spisak = List.copyOf(stanice);
        preskocene.clear();
    }

    public boolean uSpisku(Stanica s) {
        return spisak.stream().anyMatch(st -> st.uuid().equals(s.uuid()));
    }

    /** @param bezPesmeMs koliko dugo strim javlja da pesma ne ide, 0 ako ide ili ne javlja */
    public boolean reklama(long bezPesmeMs) {
        return bezPesmeMs >= ICY_BEZ_PESME_MS;
    }

    /**
     * Sledeca u spisku posle ove, u krug, bez onih koje su malopre napustene
     * zbog reklama. Ova se belezi kao napustena.
     *
     * @return null kad nema kuda - spisak je prazan ili su sve na reklamama
     */
    public Stanica sledeca(Stanica sada) {
        long ms = System.currentTimeMillis();
        preskocene.values().removeIf(t -> ms - t > ODMOR_MS);
        preskocene.put(sada.uuid(), ms);

        int mesto = -1;
        for (int i = 0; i < spisak.size(); i++) {
            if (spisak.get(i).uuid().equals(sada.uuid())) {
                mesto = i;
                break;
            }
        }
        for (int k = 1; k <= spisak.size(); k++) {
            Stanica s = spisak.get(Math.floorMod(mesto + k, spisak.size()));
            if (!preskocene.containsKey(s.uuid())) {
                return s;
            }
        }
        return null;
    }
}
