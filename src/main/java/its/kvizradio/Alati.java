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
     * Folder instalacije. Kod jpackage launcher setuje jpackage.app-path na
     * putanju .exe-a; van instalacije padamo na folder jar-a.
     */
    public static Path appDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path roditelj = Path.of(appPath).getParent();
            if (roditelj != null) {
                return roditelj;
            }
        }
        try {
            var cs = Alati.class.getProtectionDomain().getCodeSource();
            if (cs != null && "file".equals(cs.getLocation().getProtocol())) {
                Path p = Path.of(cs.getLocation().toURI());
                return Files.isDirectory(p) ? p : p.getParent();
            }
        } catch (Exception ignored) {
        }
        return Path.of(".").toAbsolutePath();
    }

    /**
     * Folder koji aplikacija nosi sa sobom (npr. "vlc"). Trazi se u instalaciji,
     * u njenom "app" podfolderu, pa navise - jpackage smesta --input sadrzaj
     * pored .exe-a, a u razvoju je isti folder projekta.
     */
    public static Path nadjiFolder(String ime) {
        Path d = appDir();
        for (int i = 0; i < 3 && d != null; i++) {
            for (Path kandidat : new Path[]{d.resolve(ime), d.resolve("app").resolve(ime)}) {
                if (Files.isDirectory(kandidat)) {
                    return kandidat;
                }
            }
            d = d.getParent();
        }
        Path uRadnom = Path.of(".").toAbsolutePath().resolve(ime);
        return Files.isDirectory(uRadnom) ? uRadnom : null;
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
