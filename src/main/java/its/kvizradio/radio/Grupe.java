package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Grupe omiljenih stanica ("Pauza", "Zagrevanje", "Kraj vecery"...).
 *
 * Grupa ne postoji sama za sebe: postoji dok je u njoj bar jedna stanica. Zato
 * se pamti samo koja stanica kojoj grupi pripada, a spisak grupa se izvodi iz
 * toga - nema praznih grupa koje bi trebalo posebno brisati.
 *
 * Redosled je redosled prvog dodeljivanja, ne azbucni: voditelj grupe pravi
 * onim redom kojim mu trebaju tokom veceri.
 */
public final class Grupe {

    private static final String FAJL = "omiljene-grupe.json";

    private final Path fajl;
    private final Consumer<String> log;
    /** uuid stanice -> naziv grupe, redosledom dodeljivanja. */
    private final Map<String, String> dodele = new LinkedHashMap<>();

    public Grupe(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
        this.fajl = Alati.podesavanjaFolder().resolve(FAJL);
        ucitaj();
    }

    /** Grupa stanice, ili prazno kad nije ni u jednoj. */
    public String grupa(Stanica s) {
        return s == null ? "" : dodele.getOrDefault(s.uuid(), "");
    }

    /** Prazan naziv vadi stanicu iz grupe. */
    public void postavi(Stanica s, String grupa) {
        if (s == null) {
            return;
        }
        if (grupa == null || grupa.isBlank()) {
            dodele.remove(s.uuid());
        } else {
            dodele.put(s.uuid(), grupa.trim());
        }
        snimi();
    }

    /** Postojece grupe, redosledom kojim su nastale. */
    public List<String> grupe() {
        List<String> imena = new ArrayList<>();
        for (String g : dodele.values()) {
            if (!imena.contains(g)) {
                imena.add(g);
            }
        }
        return imena;
    }

    private void ucitaj() {
        if (!Files.isRegularFile(fajl)) {
            return;
        }
        try {
            for (Object o : Json.lista(Json.parsiraj(Files.readString(fajl, StandardCharsets.UTF_8)))) {
                String uuid = Json.tekst(Json.mapa(o).get("stationuuid"));
                String grupa = Json.tekst(Json.mapa(o).get("grupa"));
                if (!uuid.isBlank() && !grupa.isBlank()) {
                    dodele.put(uuid, grupa);
                }
            }
        } catch (Exception e) {
            log.accept("WARNING: " + FAJL + " se ne cita (" + e.getMessage() + ")");
        }
    }

    private void snimi() {
        StringBuilder sb = new StringBuilder("[\n");
        int i = 0;
        for (Map.Entry<String, String> e : dodele.entrySet()) {
            sb.append("  {\"stationuuid\":").append(Json.navodnici(e.getKey()))
                    .append(",\"grupa\":").append(Json.navodnici(e.getValue())).append('}');
            sb.append(++i < dodele.size() ? ",\n" : "\n");
        }
        sb.append("]\n");
        try {
            Alati.upisiAtomski(fajl, sb.toString());
        } catch (Exception e) {
            log.accept("ERROR: " + FAJL + " nije snimljen (" + e.getMessage() + ")");
        }
    }
}
