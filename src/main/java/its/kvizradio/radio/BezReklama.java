package its.kvizradio.radio;

import its.kvizradio.Alati;
import its.kvizradio.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Kurirana lista mreza koje ne pustaju reklame (Radio Caprice, SomaFM, Radio
 * Paradise...). Stoji u mreze.json, ne u kodu, jer se lista dopunjuje cesce
 * nego sto se aplikacija gradi.
 *
 * API nema polje "bez reklama", pa se do njih dolazi jedino pretragom po imenu
 * mreze - zato je svaka mreza lista upita, a ne jedan.
 */
public final class BezReklama {

    public record Mreza(String naziv, List<String> upiti) {
    }

    private static final String FAJL = "mreze.json";

    private final List<Mreza> mreze = new ArrayList<>();
    private final List<String> tagovi = new ArrayList<>();

    public BezReklama(Consumer<String> log) {
        Consumer<String> zapisi = log == null ? s -> {} : log;
        String telo = ucitaj(zapisi);
        if (telo == null) {
            return;
        }
        Object koren = Json.parsiraj(telo);
        for (Object o : Json.lista(Json.put(koren, "mreze"))) {
            var m = Json.mapa(o);
            String naziv = Json.tekst(m.get("naziv"));
            List<String> upiti = new ArrayList<>();
            for (Object u : Json.lista(m.get("upiti"))) {
                upiti.add(Json.tekst(u));
            }
            if (!naziv.isBlank() && !upiti.isEmpty()) {
                mreze.add(new Mreza(naziv, upiti));
            }
        }
        for (Object t : Json.lista(Json.put(koren, "tagovi"))) {
            String tag = Json.tekst(t);
            if (!tag.isBlank()) {
                tagovi.add(tag);
            }
        }
    }

    public List<Mreza> mreze() {
        return List.copyOf(mreze);
    }

    /** Tagovi koji ulaze u istu sekciju, pod "Ostale". */
    public List<String> tagovi() {
        return List.copyOf(tagovi);
    }

    public boolean prazna() {
        return mreze.isEmpty() && tagovi.isEmpty();
    }

    /** Ugradjeni fajl, pa preko njega tvoja kopija; prvi put se kopija napravi. */
    private static String ucitaj(Consumer<String> log) {
        Path korisnicki = Alati.podesavanjaFolder().resolve(FAJL);
        if (Files.isRegularFile(korisnicki)) {
            try {
                return Files.readString(korisnicki, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.accept("WARNING: mreze.json se ne cita (" + e.getMessage() + ")");
            }
        }
        try (InputStream in = BezReklama.class.getResourceAsStream("/its/kvizradio/" + FAJL)) {
            if (in == null) {
                return null;
            }
            String telo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!Files.isRegularFile(korisnicki)) {
                Alati.upisiAtomski(korisnicki, telo);
            }
            return telo;
        } catch (Exception e) {
            log.accept("WARNING: mreze.json nije ucitan (" + e.getMessage() + ")");
            return null;
        }
    }
}
