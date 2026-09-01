package its.kvizradio.radio;

import java.util.function.Consumer;

/** Omiljene stanice. */
public final class FavoritesStore extends StaniceStore {

    public FavoritesStore(Consumer<String> log) {
        super("omiljene.json", log);
    }
}
