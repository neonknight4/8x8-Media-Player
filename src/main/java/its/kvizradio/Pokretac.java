package its.kvizradio;

import javafx.application.Application;

/**
 * Ulazna tacka.
 *
 * Postoji zato sto JVM odbija da pokrene klasu koja nasledjuje
 * {@code Application} kad je JavaFX na classpath-u ("JavaFX runtime components
 * are missing"). Iz klase koja je ne nasledjuje, {@code launch} prolazi - pa
 * NetBeans Run radi bez rucnog namestanja module path-a.
 */
public final class Pokretac {

    private Pokretac() {
    }

    public static void main(String[] args) {
        Application.launch(KvizRadioApp.class, args);
    }
}
