package its.kvizradio.ui;

import its.kvizradio.Alati;
import its.kvizradio.radio.Stanica;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Okrugli logo stanice - isti i na kartici i u donjem baru.
 *
 * Stanica koja nema upotrebljiv logo dobija crtez slusalica, a ne inicijale:
 * od 825 stanica u kesu njih 234 nema favicon, a jos 86 ih daje .ico ili .svg
 * koje JavaFX ne dekodira - inicijali su na toliko mesta izgledali kao greska.
 *
 * Slike se skidaju u pozadini i pamte dvaput: u memoriji za tekuci rad
 * aplikacije, i na disku (kes/logo) da se pri svakom otvaranju iste sekcije ne
 * vuce cetrdeset zahteva iznova. Mrtav link se pamti kao neuspeh, pa se u istoj
 * sesiji ne pokusava ponovo.
 */
public final class Logo {

    private static final Path FOLDER = Alati.podesavanjaFolder().resolve("kes").resolve("logo");

    /** SVG nema ko da rasterizuje; sve ostalo se bar pokusa. */
    private static final Set<String> NECITLJIVI = Set.of("svg", "svgz");

    private static final Map<String, Image> uPameti = new ConcurrentHashMap<>();
    private static final Set<String> propali = ConcurrentHashMap.newKeySet();

    private static final ExecutorService radnik = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "kvizradio-logo");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient klijent = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Logo() {
    }

    /** Prazan avatar zadatog precnika; sadrzaj se postavlja kroz {@link #postavi}. */
    public static StackPane avatar(double precnik) {
        Circle krug = new Circle(precnik / 2);
        krug.setFill(Color.web("#10131A"));
        krug.setStroke(Color.web("#E3B341", 0.35));

        StackPane p = new StackPane(krug, slusalice(precnik, Color.web("#E3B341", 0.75)));
        p.setPrefSize(precnik, precnik);
        p.setMinSize(precnik, precnik);
        p.setMaxSize(precnik, precnik);
        p.getProperties().put("precnik", precnik);
        return p;
    }

    /**
     * Postavlja logo stanice; dok slika ne stigne (ili ako ne stigne) stoje
     * slusalice. {@code null} vraca avatar u prazno stanje.
     */
    public static void postavi(StackPane avatar, Stanica stanica) {
        double precnik = (double) avatar.getProperties().get("precnik");
        avatar.getChildren().set(1, slusalice(precnik,
                stanica == null ? Color.web("#474B58") : Color.web("#E3B341", 0.75)));
        if (stanica == null) {
            avatar.getProperties().put("stanica", "");
            return;
        }

        String kljuc = stanica.uuid();
        avatar.getProperties().put("stanica", kljuc);
        Image odmah = uPameti.get(kljuc);
        if (odmah != null) {
            avatar.getChildren().set(1, pogled(odmah, precnik));
            return;
        }
        List<String> kandidati = kandidati(stanica);
        if (kandidati.isEmpty() || propali.contains(kljuc)) {
            return;
        }
        radnik.execute(() -> {
            Image slika = null;
            for (String url : kandidati) {
                slika = ucitaj(url);
                if (slika != null) {
                    break;
                }
            }
            if (slika == null) {
                propali.add(kljuc);
                return;
            }
            uPameti.put(kljuc, slika);
            Image nadjena = slika;
            Platform.runLater(() -> {
                // stanica je u medjuvremenu mogla da se promeni - proverava se
                // da avatar jos uvek ceka bas ovaj logo
                if (kljuc.equals(trazena(avatar))) {
                    avatar.getChildren().set(1, pogled(nadjena, precnik));
                }
            });
        });
    }

    /**
     * Odakle se logo vuce: prvo favicon iz Radio Browser-a, pa /favicon.ico sa
     * sajta stanice. Od 825 stanica u kesu njih 234 nema favicon, a dve trecine
     * tih ipak ima sajt - na uzorku od 25 takvih, /favicon.ico je vratio sliku
     * u 11 slucajeva.
     */
    private static List<String> kandidati(Stanica stanica) {
        List<String> spisak = new ArrayList<>(2);
        String favicon = stanica.favicon();
        if (favicon != null && favicon.startsWith("http") && !NECITLJIVI.contains(nastavak(favicon))) {
            spisak.add(favicon);
        }
        String sajt = stanica.sajt();
        if (sajt != null && sajt.startsWith("http")) {
            try {
                URI u = URI.create(sajt.trim());
                if (u.getHost() != null) {
                    String koren = u.getScheme() + "://" + u.getHost()
                            + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/favicon.ico";
                    if (!spisak.contains(koren)) {
                        spisak.add(koren);
                    }
                }
            } catch (IllegalArgumentException e) {
                // pokvaren URL sajta nije razlog da se odustane od favicona
            }
        }
        spisak.removeIf(propali::contains);
        return spisak;
    }

    private static String trazena(StackPane avatar) {
        Object v = avatar.getProperties().get("stanica");
        return v == null ? "" : v.toString();
    }

    /**
     * Rucno postavljen sadrzaj - lokalna numera nema logo stanice nego notu.
     */
    public static void postavi(StackPane avatar, javafx.scene.Node sadrzaj) {
        avatar.getProperties().put("stanica", "");
        avatar.getChildren().set(1, sadrzaj);
    }

    /** Sa diska ako je vec skinut, inace sa mreze pa na disk. */
    private static Image ucitaj(String url) {
        if (propali.contains(url)) {
            return null;
        }
        Path fajl = FOLDER.resolve(ime(url));
        try {
            if (Files.isRegularFile(fajl) && Files.size(fajl) > 0) {
                Image slika = slikaIzBajtova(Files.readAllBytes(fajl));
                if (slika != null) {
                    return slika;
                }
            }
        } catch (Exception e) {
            // pokvaren fajl u kesu nije razlog da se logo ne skine ponovo
        }
        try {
            HttpRequest zahtev = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "KvizRadio")
                    .GET()
                    .build();
            HttpResponse<byte[]> odgovor = klijent.send(zahtev, HttpResponse.BodyHandlers.ofByteArray());
            if (odgovor.statusCode() != 200 || odgovor.body().length == 0) {
                propali.add(url);
                return null;
            }
            Image slika = slikaIzBajtova(odgovor.body());
            if (slika == null) {
                return null;
            }
            Files.createDirectories(FOLDER);
            Files.write(fajl, odgovor.body());
            return slika;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Bajtovi u sliku. Nastavak u URL-u ne znaci nista - stanice serviraju PNG
     * pod imenom .ico i .ico pod imenom .png - pa se gleda zaglavlje.
     */
    static Image slikaIzBajtova(byte[] b) {
        if (b.length > 6 && b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) {
            return izIco(b);
        }
        Image slika = new Image(new ByteArrayInputStream(b));
        return slika.isError() || slika.getWidth() <= 0 ? null : slika;
    }

    /**
     * ICO je omotac oko vise velicina iste ikone. Uzima se najveca; unutra je
     * ili ceo PNG (Vista pa navise) ili goli DIB, koji JavaFX ne cita sam.
     */
    private static Image izIco(byte[] b) {
        int broj = u16(b, 4);
        int najbolji = -1;
        long najveca = -1;
        for (int i = 0; i < broj; i++) {
            int stavka = 6 + i * 16;
            if (stavka + 16 > b.length) {
                break;
            }
            int sirina = (b[stavka] & 0xFF) == 0 ? 256 : b[stavka] & 0xFF;
            int visina = (b[stavka + 1] & 0xFF) == 0 ? 256 : b[stavka + 1] & 0xFF;
            long povrsina = (long) sirina * visina;
            if (povrsina > najveca) {
                najveca = povrsina;
                najbolji = stavka;
            }
        }
        if (najbolji < 0) {
            return null;
        }
        int duzina = u32(b, najbolji + 8);
        int pomak = u32(b, najbolji + 12);
        if (pomak <= 0 || duzina <= 0 || pomak + duzina > b.length) {
            return null;
        }
        byte[] telo = Arrays.copyOfRange(b, pomak, pomak + duzina);
        if (telo.length > 8 && (telo[0] & 0xFF) == 0x89 && telo[1] == 'P' && telo[2] == 'N' && telo[3] == 'G') {
            Image slika = new Image(new ByteArrayInputStream(telo));
            return slika.isError() || slika.getWidth() <= 0 ? null : slika;
        }
        return izDib(telo);
    }

    /**
     * DIB iz ICO-a: visina u zaglavlju je dvostruka jer ispod slike stoji AND
     * maska, redovi idu odozdo nagore, a kanali su BGR(A). Citaju se samo 24 i
     * 32 bita - paletne ikone su danas retke, a za njih ostaju slusalice.
     */
    private static Image izDib(byte[] d) {
        if (d.length < 40) {
            return null;
        }
        int velZaglavlja = u32(d, 0);
        int sirina = u32(d, 4);
        int visina = u32(d, 8) / 2;
        int bita = u16(d, 14);
        if (sirina <= 0 || visina <= 0 || sirina > 512 || visina > 512
                || (bita != 32 && bita != 24 && bita != 8 && bita != 4 && bita != 1)) {
            return null;
        }
        // paletne ikone nose tabelu boja odmah iza zaglavlja, po 4 bajta (BGRX)
        int bojaUPaleti = bita <= 8 ? (u32(d, 32) != 0 ? u32(d, 32) : 1 << bita) : 0;
        int[] paleta = new int[bojaUPaleti];
        for (int i = 0; i < bojaUPaleti; i++) {
            int p = velZaglavlja + i * 4;
            if (p + 3 >= d.length) {
                return null;
            }
            paleta[i] = ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
        }
        int pocetak = velZaglavlja + bojaUPaleti * 4;
        int red = (sirina * bita + 31) / 32 * 4;
        if (pocetak + (long) red * visina > d.length) {
            return null;
        }
        // maska providnosti stoji iza slike, jedan bit po pikselu
        int redMaske = (sirina + 31) / 32 * 4;
        int pocetakMaske = pocetak + red * visina;
        boolean imaMasku = bita <= 8 && pocetakMaske + (long) redMaske * visina <= d.length;

        int[] piksela = new int[sirina * visina];
        boolean imaAlfu = bita != 32;
        for (int y = 0; y < visina; y++) {
            int izvor = pocetak + (visina - 1 - y) * red;
            int izvorMaske = pocetakMaske + (visina - 1 - y) * redMaske;
            for (int x = 0; x < sirina; x++) {
                int boja;
                int a = 255;
                if (bita >= 24) {
                    int p = izvor + x * (bita / 8);
                    a = bita == 32 ? d[p + 3] & 0xFF : 255;
                    imaAlfu |= a != 0;
                    boja = ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
                } else {
                    int indeks = citajIndeks(d, izvor, x, bita);
                    boja = indeks < paleta.length ? paleta[indeks] : 0;
                    if (imaMasku && (d[izvorMaske + x / 8] >> (7 - x % 8) & 1) == 1) {
                        a = 0;
                    }
                }
                piksela[y * sirina + x] = (a << 24) | boja;
            }
        }
        // stari 32-bitni ICO ume da ostavi alfu na nuli; tada je slika neprozirna
        if (!imaAlfu) {
            for (int i = 0; i < piksela.length; i++) {
                piksela[i] |= 0xFF000000;
            }
        }
        WritableImage slika = new WritableImage(sirina, visina);
        slika.getPixelWriter().setPixels(0, 0, sirina, visina,
                PixelFormat.getIntArgbInstance(), piksela, 0, sirina);
        return slika;
    }

    /** Indeks boje iz reda sa 1, 4 ili 8 bita po pikselu. */
    private static int citajIndeks(byte[] d, int izvor, int x, int bita) {
        return switch (bita) {
            case 8 -> d[izvor + x] & 0xFF;
            case 4 -> (d[izvor + x / 2] >> (x % 2 == 0 ? 4 : 0)) & 0x0F;
            default -> (d[izvor + x / 8] >> (7 - x % 8)) & 1;
        };
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    /**
     * Slika u krugu, sredinom na sredini. Krug za secenje ide na omotac, a ne
     * na ImageView: uz preserveRatio pravougaoni logo se iscrta nize od svojih
     * okvira, pa bi krug u koordinatama slike sekao ukrivo.
     */
    private static StackPane pogled(Image slika, double precnik) {
        double s = precnik - 4;
        // slika ide u 80% precnika, ne u ceo krug: logoi su cesto siroki i bez
        // sopstvene margine, pa su im krajevi zavrsavali pod krugom
        double stane = precnik * 0.8;
        ImageView pogled = new ImageView(slika);
        pogled.setFitWidth(stane);
        pogled.setFitHeight(stane);
        pogled.setPreserveRatio(true);
        pogled.setSmooth(true);

        StackPane maska = new StackPane(pogled);
        maska.setPrefSize(s, s);
        maska.setMinSize(s, s);
        maska.setMaxSize(s, s);
        maska.setClip(new Circle(s / 2, s / 2, s / 2));
        return maska;
    }

    /** Slusalice: luk preko glave i dve skoljke, sve u odnosu na precnik. */
    private static Group slusalice(double precnik, Color boja) {
        double r = precnik * 0.26;
        double debljina = Math.max(1.6, precnik * 0.05);

        Arc luk = new Arc(0, 0, r, r, 0, 180);
        luk.setType(ArcType.OPEN);
        luk.setFill(null);
        luk.setStroke(boja);
        luk.setStrokeWidth(debljina);
        luk.setStrokeLineCap(StrokeLineCap.ROUND);

        double sirina = Math.max(3, precnik * 0.11);
        double visina = Math.max(6, precnik * 0.22);
        Rectangle leva = skoljka(-r, visina, sirina, boja);
        Rectangle desna = skoljka(r - sirina, visina, sirina, boja);

        // bez pomeranja: StackPane centrira Group po njegovim granicama, pa je
        // svako "doterivanje" po visini cista greska
        return new Group(luk, leva, desna);
    }

    private static Rectangle skoljka(double x, double visina, double sirina, Color boja) {
        Rectangle r = new Rectangle(x, -visina * 0.15, sirina, visina);
        r.setArcWidth(sirina);
        r.setArcHeight(sirina);
        r.setFill(boja);
        return r;
    }

    /** Ime fajla u kesu: citljiv nastavak, a jedinstvenost nosi hes URL-a. */
    private static String ime(String url) {
        return hes(url) + "." + (nastavak(url).isBlank() ? "img" : nastavak(url));
    }

    private static String nastavak(String url) {
        String bezUpita = url.split("\\?")[0];
        int tacka = bezUpita.lastIndexOf('.');
        int kosa = bezUpita.lastIndexOf('/');
        if (tacka < 0 || tacka < kosa || bezUpita.length() - tacka > 6) {
            return "";
        }
        return bezUpita.substring(tacka + 1).toLowerCase();
    }

    private static String hes(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
