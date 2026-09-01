package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spisak stanica u jednom JSON fajlu, redosledom kojim su dodate.
 *
 * Zapis je ista struktura kao odgovor API-ja, pa se cita istim {@link Stanica#iz}
 * - i pri padu mreze se ove stanice i dalje mogu pustiti, bez ijednog poziva.
 */
abstract class StaniceStore {

    private final Path fajl;
    private final Consumer<String> log;
    private final List<Stanica> stanice = new ArrayList<>();

    StaniceStore(String imeFajla, Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
        this.fajl = Alati.podesavanjaFolder().resolve(imeFajla);
        ucitaj();
    }

    public List<Stanica> sve() {
        return List.copyOf(stanice);
    }

    public boolean jeste(Stanica s) {
        return s != null && stanice.stream().anyMatch(o -> o.uuid().equals(s.uuid()));
    }

    /** Dodaje ako je nema, sklanja ako je ima; vraca novo stanje. */
    public boolean prebaci(Stanica s) {
        if (jeste(s)) {
            stanice.removeIf(o -> o.uuid().equals(s.uuid()));
            snimi();
            return false;
        }
        stanice.add(s);
        snimi();
        return true;
    }

    private void ucitaj() {
        if (!Files.isRegularFile(fajl)) {
            return;
        }
        try {
            for (Object o : Json.lista(Json.parsiraj(Files.readString(fajl, StandardCharsets.UTF_8)))) {
                Stanica s = Stanica.iz(o);
                if (s.upotrebljiva()) {
                    stanice.add(s);
                }
            }
        } catch (Exception e) {
            log.accept("WARNING: " + fajl.getFileName() + " se ne cita (" + e.getMessage() + ")");
        }
    }

    private void snimi() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < stanice.size(); i++) {
            sb.append("  ").append(stanice.get(i).uJson());
            sb.append(i < stanice.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        try {
            Alati.upisiAtomski(fajl, sb.toString());
        } catch (Exception e) {
            log.accept("ERROR: " + fajl.getFileName() + " nije snimljen (" + e.getMessage() + ")");
        }
    }
}
