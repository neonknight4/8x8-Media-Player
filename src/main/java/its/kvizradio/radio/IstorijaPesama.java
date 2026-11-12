package its.kvizradio.radio;

import its.kvizradio.Alati;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pesme koje su isle na traci, u {@code pesme.txt} pored ostalih podesavanja.
 * Jedan red po pesmi:
 *
 * <pre>2026-11-12 21:42:05 | Naxi Radio | Oliver Mandić - Poludeću</pre>
 *
 * Obican tekst, da se cita i pretrazuje i bez aplikacije. Samo se dopisuje -
 * pad u pola upisa ostavi najvise jedan krnji red, koji se pri citanju
 * preskoci.
 *
 * Upisuje se samo "Izvodjac - Naslov", isto pravilo kao kod preskakanja
 * reklama: tekst bez izvodjaca je najcesce reklama ili sajt stanice.
 */
public final class IstorijaPesama {

    private static final String FAJL = "pesme.txt";
    private static final String RAZDVAJAC = " | ";
    private static final DateTimeFormatter VREME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Zapis(LocalDateTime vreme, String stanica, String pesma) {
    }

    private final Path fajl;
    private final Consumer<String> log;
    /** Poslednja upisana - strim se cita na 15s, a Shazam vraca istu pesmu vise puta. */
    private String poslednja = "";

    public IstorijaPesama(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
        this.fajl = Alati.podesavanjaFolder().resolve(FAJL);
    }

    public Path fajl() {
        return fajl;
    }

    /** Upisuje pesmu ako je nova; true kad je red upisan. */
    public boolean zabelezi(Stanica s, Pesma p) {
        if (s == null || p == null || p.prazna() || p.izvodjac().isBlank()) {
            return false;
        }
        String kljuc = s.uuid() + '\n' + p.izvodjac() + '\n' + p.naslov();
        if (kljuc.equals(poslednja)) {
            return false;
        }
        poslednja = kljuc;
        // razdvajac ne sme da se pojavi u imenu stanice, inace se red ne procita nazad
        String red = LocalDateTime.now().format(VREME)
                + RAZDVAJAC + jedanRed(s.ime()).replace('|', '/')
                + RAZDVAJAC + jedanRed(p.izvodjac()) + " - " + jedanRed(p.naslov())
                + System.lineSeparator();
        try {
            Files.createDirectories(fajl.getParent());
            Files.writeString(fajl, red, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.accept("ERROR: " + FAJL + " - " + e.getMessage());
            return false;
        }
    }

    /** Poslednjih {@code najvise} zapisa, najnoviji prvi. */
    public List<Zapis> poslednji(int najvise) {
        if (!Files.isRegularFile(fajl)) {
            return List.of();
        }
        List<String> redovi;
        try {
            // ne readAllLines: fajl sacuvan iz editora u drugom kodiranju bi ga oborio
            redovi = new String(Files.readAllBytes(fajl), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            log.accept("ERROR: " + FAJL + " - " + e.getMessage());
            return List.of();
        }
        List<Zapis> zapisi = new ArrayList<>();
        for (int i = redovi.size() - 1; i >= 0 && zapisi.size() < najvise; i--) {
            Zapis z = procitaj(redovi.get(i));
            if (z != null) {
                zapisi.add(z);
            }
        }
        return zapisi;
    }

    private static Zapis procitaj(String red) {
        String[] delovi = red.split(" \\| ", 3);
        if (delovi.length < 3) {
            return null;
        }
        try {
            return new Zapis(LocalDateTime.parse(delovi[0].trim(), VREME), delovi[1].trim(), delovi[2].trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String jedanRed(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
