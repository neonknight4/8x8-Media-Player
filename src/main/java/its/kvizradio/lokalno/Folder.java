package its.kvizradio.lokalno;

import java.nio.file.Path;
import java.util.List;

/** Folder sa muzikom, onako kako je zaveden u folderi.json. */
public record Folder(String naziv, Path putanja, List<Numera> numere) {

    public boolean postoji() {
        return java.nio.file.Files.isDirectory(putanja);
    }
}
