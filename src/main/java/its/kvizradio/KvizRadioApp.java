package its.kvizradio;

import its.kvizradio.player.PlayerService;
import its.kvizradio.radio.BezReklama;
import its.kvizradio.radio.FavoritesStore;
import its.kvizradio.radio.Meni;
import its.kvizradio.radio.Odeljak;
import its.kvizradio.radio.RadioBrowserService;
import its.kvizradio.radio.Sekcija;
import its.kvizradio.radio.Stanica;
import its.kvizradio.ui.Kartica;
import its.kvizradio.ui.PlayerBar;
import its.kvizradio.ui.Sidebar;
import its.kvizradio.ui.Tekst;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * KvizRadio - puštanje online radija izmedju rundi pab kviza.
 *
 * Sav posao sa mrezom i zvukom radi {@code radio}/{@code player} paket; ovde je
 * samo raspored i to da se nista od toga ne desava na JavaFX niti.
 */
public class KvizRadioApp extends Application {

    private final List<String> log = new ArrayList<>();
    private final RadioBrowserService api = new RadioBrowserService(this::zabelezi);
    private final FavoritesStore omiljene = new FavoritesStore(this::zabelezi);
    private final BezReklama bezReklama = new BezReklama(this::zabelezi);

    private PlayerService player;
    private Sidebar sidebar;
    private PlayerBar bar;

    private final Label mrvica = new Label();
    private final Label naslov = new Label();
    private final Label podnaslov = new Label();
    private final TextField pretraga = new TextField();
    private final VBox sadrzaj = new VBox(26);
    private ScrollPane skrol;

    /** Kartice trenutnog prikaza, po uuid-u - da se aktivna oznaci bez ponovnog crtanja. */
    private final Map<String, Kartica> kartice = new LinkedHashMap<>();

    /** Sta je poslednje ucitano - za lokalni filter, bez novog poziva API-ja. */
    private List<Odeljak> ucitano = List.of();
    private boolean lokalniFilter;
    private Stanica izabrana;
    private int limit = 40;

    @Override
    public void start(Stage stage) {
        Properties konf = Podesavanja.konfiguracija();
        limit = Podesavanja.broj(konf, "limit", 40);

        player = new PlayerService(st -> Platform.runLater(() -> osveziStanje(st)), this::zabelezi);

        bar = new PlayerBar(this::dugmePlayStop, this::fadeOut, v -> player.jacina(v));
        sidebar = new Sidebar(this::otvori);
        sidebar.postavi(grupe(konf));

        BorderPane koren = new BorderPane();
        koren.getStyleClass().add("pozadina");
        koren.setLeft(sidebar);
        koren.setCenter(sredina());
        koren.setBottom(dno());

        Properties stanje = Podesavanja.stanje();
        bar.postaviJacinu(Podesavanja.broj(stanje, "jacina", 70));
        player.jacina(bar.jacina());
        izabrana = poslednjaStanica(stanje);
        bar.pripremi(izabrana);

        Scene scena = new Scene(koren, 1280, 800);
        scena.getStylesheets().add(KvizRadioApp.class.getResource("style.css").toExternalForm());
        scena.addEventFilter(KeyEvent.KEY_PRESSED, this::precice);

        stage.setTitle("KvizRadio " + Alati.verzija());
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        stage.setScene(scena);
        stage.setOnCloseRequest(e -> {
            Podesavanja.snimiStanje(bar.jacina(), izabrana == null ? null : izabrana.uJson());
            player.oslobodi();
        });
        stage.show();

        sidebar.broj("Omiljene", omiljene.sve().size());
        sidebar.izaberiPrvu();
    }

    // ----------------------------------------------------------- raspored

    private Region sredina() {
        mrvica.getStyleClass().add("mrvica");
        naslov.getStyleClass().add("naslov");
        podnaslov.getStyleClass().add("podnaslov");

        VBox tekst = new VBox(8, mrvica, naslov, podnaslov);
        tekst.setAlignment(Pos.BOTTOM_LEFT);

        pretraga.getStyleClass().add("pretraga");
        pretraga.setPromptText("Trazi stanicu...");
        pretraga.setPrefWidth(320);
        pretraga.setMinWidth(320);
        // bez ovoga polje uzme fokus pri pokretanju, pa Space ode u tekst
        // umesto na play/stop - a to je precica koja mora da radi odmah
        pretraga.setFocusTraversable(false);
        pretraga.setOnAction(e -> traziPoImenu());
        pretraga.textProperty().addListener((o, staro, novo) -> {
            if (lokalniFilter) {
                nacrtaj(filtrirano(ucitano, novo));
            }
        });

        Region razmak = new Region();
        HBox.setHgrow(razmak, Priority.ALWAYS);
        HBox zaglavlje = new HBox(32, tekst, razmak, pretraga);
        zaglavlje.setAlignment(Pos.BOTTOM_LEFT);
        zaglavlje.getStyleClass().add("sadrzaj-zaglavlje");

        sadrzaj.setPadding(new Insets(26, 40, 34, 40));
        skrol = new ScrollPane(sadrzaj);
        skrol.setFitToWidth(true);
        skrol.getStyleClass().add("mreza");
        skrol.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(skrol, Priority.ALWAYS);

        VBox box = new VBox(zaglavlje, skrol);
        VBox.setVgrow(skrol, Priority.ALWAYS);
        return box;
    }

    private Region dno() {
        Label precice = new Label("Space play/stop   ·   F fade   ·   strelice volume");
        precice.getStyleClass().add("precice");
        HBox red = new HBox(precice);
        red.setAlignment(Pos.CENTER_RIGHT);
        red.setPadding(new Insets(0, 34, 8, 0));
        return new VBox(red, bar);
    }

    /** Grupe iz konfiguracije, pa mreze bez reklama, pa omiljene. */
    private List<Meni.Grupa> grupe(Properties konf) {
        List<Meni.Grupa> grupe = new ArrayList<>(Meni.ucitaj(konf));

        if (!bezReklama.prazna()) {
            List<Sekcija> stavke = new ArrayList<>();
            stavke.add(Sekcija.bezReklama("Sve mreze", null));
            for (BezReklama.Mreza m : bezReklama.mreze()) {
                stavke.add(Sekcija.bezReklama(m.naziv(), m.naziv()));
            }
            grupe.add(new Meni.Grupa("Bez reklama", stavke));
        }

        grupe.add(new Meni.Grupa("Omiljene", List.of(Sekcija.omiljene())));
        return grupe;
    }

    // -------------------------------------------------------------- prikaz

    /** Klik u levom meniju: naslovi se postave odmah, stanice stizu iz pozadine. */
    private void otvori(String grupa, Sekcija sekcija) {
        pretraga.clear();
        lokalniFilter = sekcija.vrsta() != Sekcija.Vrsta.PRETRAGA;

        mrvica.setText(Tekst.razmaknuto((grupa + " / " + sekcija.naziv()).toUpperCase()));
        naslov.setText(sekcija.naziv());

        ucitaj(() -> switch (sekcija.vrsta()) {
            case OMILJENE -> List.of(Odeljak.bezNaslova(omiljene.sve()));
            case BEZ_REKLAMA -> api.bezReklama(bezReklama, sekcija.drzava(), limit);
            case PRETRAGA -> List.of(Odeljak.bezNaslova(api.pretraga(sekcija, limit)));
        });
    }

    /** Enter u polju za pretragu - ovo ide na API, po imenu stanice. */
    private void traziPoImenu() {
        String upit = pretraga.getText().trim();
        if (upit.isEmpty() || lokalniFilter) {
            return;
        }
        mrvica.setText(Tekst.razmaknuto("PRETRAGA"));
        naslov.setText(upit);
        ucitaj(() -> List.of(Odeljak.bezNaslova(api.pretraga(null, null, upit, limit))));
    }

    /**
     * Mreza ide van JavaFX niti; dok traje, stoji "Ucitavam...". Rezultat se
     * vraca na FX nit, jer se tek tada prave kartice.
     */
    private void ucitaj(Supplier<List<Odeljak>> izvor) {
        podnaslov.setText("Ucitavam...");
        sadrzaj.getChildren().clear();
        kartice.clear();

        CompletableFuture.supplyAsync(izvor).whenComplete((odeljci, greska) -> Platform.runLater(() -> {
            if (greska != null) {
                zabelezi("ERROR: " + greska.getMessage());
                ucitano = List.of();
            } else {
                ucitano = odeljci;
            }
            nacrtaj(ucitano);
        }));
    }

    private void nacrtaj(List<Odeljak> odeljci) {
        sadrzaj.getChildren().clear();
        kartice.clear();

        int ukupno = odeljci.stream().mapToInt(o -> o.stanice().size()).sum();
        podnaslov.setText(ukupno + (ukupno == 1 ? " stanica" : " stanica") + " · klik pusta uzivo");

        if (ukupno == 0) {
            sadrzaj.getChildren().add(prazno());
            return;
        }

        for (Odeljak o : odeljci) {
            if (o.stanice().isEmpty()) {
                continue;
            }
            if (o.naziv() != null) {
                Label naslovOdeljka = new Label(Tekst.razmaknuto(o.naziv().toUpperCase()));
                naslovOdeljka.getStyleClass().add("mrvica");
                sadrzaj.getChildren().add(naslovOdeljka);
            }
            FlowPane mreza = new FlowPane(18, 18);
            for (Stanica s : o.stanice()) {
                Kartica k = new Kartica(s, omiljene.jeste(s), this::pusti, this::prebaciOmiljenu);
                kartice.put(s.uuid(), k);
                mreza.getChildren().add(k);
            }
            sadrzaj.getChildren().add(mreza);
        }
        osveziAktivnuKarticu();
    }

    private Region prazno() {
        boolean uOmiljenima = "Omiljene".equals(naslov.getText());
        Label znak = new Label(uOmiljenima ? "★" : "∅");
        znak.getStyleClass().add("prazno-znak");
        Label naslovPraznog = new Label(uOmiljenima
                ? "Jos nema omiljenih stanica"
                : "Nema stanice za taj pojam.");
        naslovPraznog.getStyleClass().add("prazno-naslov");
        Label opis = new Label(uOmiljenima
                ? "Klikni zvezdicu na kartici stanice i naci ces je ovde - spremna za sledeci kviz."
                : "Probaj drugu rec ili drugu sekciju u levom meniju.");
        opis.getStyleClass().add("prazno-tekst");
        opis.setWrapText(true);
        opis.setMaxWidth(320);
        opis.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(18, znak, naslovPraznog, opis);
        box.setAlignment(Pos.CENTER);
        box.setMinHeight(420);
        return box;
    }

    /** Lokalni filter po imenu i tagovima - za omiljene i mreze bez reklama. */
    private static List<Odeljak> filtrirano(List<Odeljak> odeljci, String upit) {
        String q = upit == null ? "" : upit.trim().toLowerCase();
        if (q.isEmpty()) {
            return odeljci;
        }
        List<Odeljak> izlaz = new ArrayList<>();
        for (Odeljak o : odeljci) {
            List<Stanica> pogodjene = o.stanice().stream()
                    .filter(s -> s.ime().toLowerCase().contains(q) || s.tagovi().toLowerCase().contains(q))
                    .toList();
            if (!pogodjene.isEmpty()) {
                izlaz.add(new Odeljak(o.naziv(), pogodjene));
            }
        }
        return izlaz;
    }

    // -------------------------------------------------------------- radnje

    private void pusti(Stanica s) {
        izabrana = s;
        player.pusti(s);
        // brojac klikova je API-ju znak da je stanica ziva; ne sme da drzi UI
        CompletableFuture.runAsync(() -> api.klik(s));
    }

    private void dugmePlayStop() {
        if (player.stanje() != PlayerService.Stanje.STOP) {
            player.stop();
            return;
        }
        Stanica sledeca = izabrana != null ? izabrana : prvaVidljiva();
        if (sledeca != null) {
            pusti(sledeca);
        }
    }

    private void fadeOut() {
        if (player.stanje() == PlayerService.Stanje.STOP) {
            return;
        }
        bar.oznaciFade();
        player.fadeOut(2000);
    }

    private void prebaciOmiljenu(Stanica s) {
        boolean sada = omiljene.prebaci(s);
        Kartica k = kartice.get(s.uuid());
        if (k != null) {
            k.oznaciOmiljenu(sada);
        }
        sidebar.broj("Omiljene", omiljene.sve().size());
    }

    private Stanica prvaVidljiva() {
        return kartice.values().stream().findFirst().map(Kartica::stanica).orElse(null);
    }

    private void osveziStanje(PlayerService.Status st) {
        bar.prikazi(st);
        osveziAktivnuKarticu();
    }

    private void osveziAktivnuKarticu() {
        boolean radi = player.stanje() != PlayerService.Stanje.STOP;
        Stanica sada = player.stanica();
        for (Kartica k : kartice.values()) {
            boolean ista = radi && sada != null && sada.uuid().equals(k.stanica().uuid());
            k.oznaciAktivnu(ista, ista && player.stanje() == PlayerService.Stanje.SVIRA);
        }
    }

    /**
     * Space, F i strelice rade sa bilo kog mesta u prozoru - osim dok se kuca u
     * polju za pretragu, gde razmak i strelice pripadaju tekstu.
     */
    private void precice(KeyEvent e) {
        if (pretraga.isFocused()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                pretraga.clear();
                skrol.requestFocus();
                e.consume();
            }
            return;
        }
        switch (e.getCode()) {
            case SPACE -> {
                dugmePlayStop();
                e.consume();
            }
            case F -> {
                fadeOut();
                e.consume();
            }
            case UP, RIGHT -> {
                bar.postaviJacinu(Math.min(100, bar.jacina() + 5));
                e.consume();
            }
            case DOWN, LEFT -> {
                bar.postaviJacinu(Math.max(0, bar.jacina() - 5));
                e.consume();
            }
            default -> {
            }
        }
    }

    private Stanica poslednjaStanica(Properties stanje) {
        String json = stanje.getProperty("stanica", "");
        if (json.isBlank()) {
            return null;
        }
        try {
            Stanica s = Stanica.iz(Json.parsiraj(json));
            return s.upotrebljiva() ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Log za sada ide u konzolu; kad ovo udje u HUB, umesto ovoga stoji njegov
     * LogView.
     */
    private void zabelezi(String poruka) {
        log.add(poruka);
        System.out.println(poruka);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
