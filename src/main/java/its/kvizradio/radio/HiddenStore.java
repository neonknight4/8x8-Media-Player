package its.kvizradio.radio;

import java.util.function.Consumer;

/**
 * Sakrivene stanice - one koje ne treba da smetaju u listi (mrtve, reklame,
 * pogresan zanr), ali se ne brisu.
 *
 * Nigde se ne pamti odakle je koja sklonjena: sekcije su pretrage API-ja, pa
 * cim stanica izadje iz ovog spiska, opet se pojavi tamo gde joj je i mesto.
 */
public final class HiddenStore extends StaniceStore {

    public HiddenStore(Consumer<String> log) {
        super("sakrivene.json", log);
    }
}
