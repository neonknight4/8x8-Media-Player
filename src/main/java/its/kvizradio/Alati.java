package its.kvizradio;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zajednicke sitnice, po ugledu na HUB: gde stoje podesavanja i kes, kako se
 * fajl upisuje bez rizika da ostane odsecen.
 */
public final class Alati {

    public static final boolean WINDOWS
            = System.getProperty("os.name").toLowerCase().contains("win");

    private Alati() {
    }

    /**
     * Folder sa korisnickim podesavanjima, kesom i omiljenima. Van instalacije,
     * jer update instalera brise sve sto je instaler doneo.
     */
    public static Path podesavanjaFolder() {
        String appdata = System.getenv("APPDATA");
        Path baza = (WINDOWS && appdata != null && !appdata.isBlank())
                ? Path.of(appdata)
                : Path.of(System.getProperty("user.home"), ".config");
        return baza.resolve("KvizRadio");
    }

    /** Verzija koju postavi jpackage launcher; van instalacije "razvojna". */
    public static String verzija() {
        String v = System.getProperty("jpackage.app-version");
        return (v == null || v.isBlank()) ? "razvojna" : v;
    }

    /**
     * Upisuje tekst preko postojeceg fajla, ali tek kad je nov ceo na disku.
     * Direktan upis bi pri padu u pola posla ostavio pokvaren kes ili omiljene.
     */
    public static void upisiAtomski(Path fajl, String sadrzaj) throws java.io.IOException {
        Files.createDirectories(fajl.getParent());
        Path privremeni = fajl.resolveSibling(fajl.getFileName() + ".novi");
        Files.writeString(privremeni, sadrzaj, java.nio.charset.StandardCharsets.UTF_8);
        Files.move(privremeni, fajl, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
