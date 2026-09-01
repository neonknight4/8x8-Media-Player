package its.kvizradio.lokalno;

import its.kvizradio.radio.Pesma;

import java.nio.file.Path;

/**
 * Jedna pesma sa diska.
 *
 * Zove se numera da se ne mesa sa {@link Pesma}, koja opisuje sta trenutno ide
 * (svejedno da li sa radija ili sa diska).
 */
public record Numera(Path putanja, String izvodjac, String naslov, String album,
        int trajanjeSek, boolean izTaga) {

    /**
     * Da li podaci dolaze iz taga ili su izvuceni iz imena fajla.
     *
     * Ne moze da se zakljuci iz toga sto izvodjac nije prazan: i ime fajla
     * "Bijelo Dugme - Ni Na Nebu.mp3" da izvodjaca, a taga nema. Bas te pesme
     * su te kojima PREPOZNAJ ima sta da doda.
     */
    public boolean imaTag() {
        return izTaga;
    }

    public Pesma pesma() {
        return new Pesma(izvodjac, naslov, Pesma.IZ_STRIMA);
    }

    public String opis() {
        return izvodjac.isBlank() ? naslov : izvodjac + " - " + naslov;
    }

    /** "3:07"; nula znaci da trajanje nije poznato. */
    public String trajanje() {
        if (trajanjeSek <= 0) {
            return "";
        }
        return trajanjeSek / 60 + ":" + String.format("%02d", trajanjeSek % 60);
    }
}
