package its.kvizradio.radio;

import java.util.List;

/**
 * Naslovljena grupa kartica. Vecina sekcija ima jedan odeljak bez naslova; kod
 * "Bez reklama" ih je vise, po jedan za svaku mrezu.
 */
public record Odeljak(String naziv, List<Stanica> stanice) {

    public static Odeljak bezNaslova(List<Stanica> stanice) {
        return new Odeljak(null, stanice);
    }
}
