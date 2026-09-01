package its.kvizradio;

import its.kvizradio.player.PlayerService;
import its.kvizradio.radio.BezReklama;
import its.kvizradio.radio.FavoritesStore;
import its.kvizradio.lokalno.Biblioteka;
import its.kvizradio.lokalno.Folder;
import its.kvizradio.lokalno.Numera;
import its.kvizradio.lokalno.RedSviranja;
import its.kvizradio.radio.Grupe;
import its.kvizradio.radio.HiddenStore;
import its.kvizradio.radio.Meni;
import its.kvizradio.radio.Odeljak;
import its.kvizradio.radio.Pesma;
import its.kvizradio.radio.PrepoznajService;
import its.kvizradio.radio.PrepoznajService;
import its.kvizradio.radio.RadioBrowserService;
import its.kvizradio.radio.Sekcija;
import its.kvizradio.radio.Stanica;
import its.kvizradio.ui.FolderKartica;
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
    private final HiddenStore sakrivene = new HiddenStore(this::zabelezi);
    private final Grupe grupe = new Grupe(this::zabelezi);
    private final Biblioteka biblioteka = new Biblioteka(this::zabelezi);

    /** Red sviranja po folderu - da se folder nastavi tamo gde je stao. */
    private final Map<java.nio.file.Path, RedSviranja> redovi = new LinkedHashMap<>();
    private RedSviranja tekuciRed;
    private Numera tekucaNumera;
    private Folder otvoreniFolder;
    /** Iz kog foldera ide tekuca numera - pise u donjem baru. */
    private String tekuciFolder = "";
    private final BezReklama bezReklama = new BezReklama(this::zabelezi);

    private PlayerService player;
    private PrepoznajService prepoznavanje;
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
    /** Odakle je ucitano - da se prikaz osvezi bez pamcenja koja je stavka kliknuta. */
    private Supplier<List<Odeljak>> izvorPrikaza;
    private Sekcija.Vrsta vrstaPrikaza = Sekcija.Vrsta.PRETRAGA;
    private boolean lokalniFilter;
    private Stanica izabrana;
    /** Stanica za koju prepoznavanje upravo traje; null znaci da ne traje. */
    private Stanica prepoznajemZa;
    private int limit = 40;

    @Override
    public void start(Stage stage) {
        Properties konf = Podesavanja.konfiguracija();
        limit = Podesavanja.broj(konf, "limit", 40);

        try {
            player = new PlayerService(st -> Platform.runLater(() -> osveziStanje(st)), this::zabelezi);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            // bez libvlc-a nema sta da se pusti; bolje jasna poruka nego stack
            // trace u konzoli koju na kvizu niko ne gleda
            zabelezi("ERROR: libvlc nije nadjen (" + e.getMessage() + ")");
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                    "VLC (libvlc) nije nadjen.\n\nKvizRadio svira preko VLC-a. Instaliraj VLC "
                    + "za Windows, ili koristi instaler koji ga nosi sa sobom.").showAndWait();
            Platform.exit();
            return;
        }

        prepoznavanje = new PrepoznajService(konf.getProperty("prepoznavanje.servis", "audd"),
                konf.getProperty("prepoznavanje.apiKey", ""),
                konf.getProperty("prepoznavanje.python", ""), this::zabelezi);
        bar = new PlayerBar(this::dugmePlayStop, this::fadeOut, this::jacina,
                this::prebaciPrigusenje, this::prepoznajPesmu,
                () -> player.nivoi(), PlayerService.TRAKA,
                this::prethodnaNumera, this::sledecaNumera, player::premotaj);

        player.postaviSlusaocaNapretka(n -> Platform.runLater(
                () -> bar.napredak(n.protekloMs(), n.ukupnoMs())));
        player.postaviKrajNumere(() -> Platform.runLater(this::sledecaNumera));
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
        stage.getIcons().addAll(ikone());
        // spektar je usao izmedju levog bloka i jacine, pa uzi prozor nema gde
        stage.setMinWidth(1120);
        stage.setMinHeight(640);
        stage.setScene(scena);
        stage.setOnCloseRequest(e -> {
            Podesavanja.snimiStanje(bar.jacina(), izabrana == null ? null : izabrana.uJson());
            player.oslobodi();
        });
        stage.show();

        sidebar.broj("Omiljene", omiljene.sve().size());
        sidebar.broj("Sakrivene", sakrivene.sve().size());
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
        // tekst se menja po sekciji - u muzici se ne traze stanice
        pretraga.setPrefWidth(320);
        pretraga.setMinWidth(320);
        // bez ovoga polje uzme fokus pri pokretanju, pa Space ode u tekst
        // umesto na play/stop - a to je precica koja mora da radi odmah
        pretraga.setFocusTraversable(false);
        pretraga.setOnAction(e -> traziPoImenu());
        pretraga.textProperty().addListener((o, staro, novo) -> {
            if (vrstaPrikaza == Sekcija.Vrsta.LOKALNE_PESME) {
                nacrtajPesme(otvoreniFolder, novo);
            } else if (lokalniFilter) {
                nacrtaj(filtrirano(ucitano, novo));
            }
        });

        Region razmak = new Region();
        HBox.setHgrow(razmak, Priority.ALWAYS);
        HBox zaglavlje = new HBox(32, tekst, razmak, pretraga);
        zaglavlje.setAlignment(Pos.BOTTOM_LEFT);
        zaglavlje.getStyleClass().add("sadrzaj-zaglavlje");

        sadrzaj.setPadding(new Insets(26, 40, 34, 40));
        // klik na praznu povrsinu takodje vraca fokus sa polja za pretragu
        sadrzaj.setOnMouseClicked(e -> skrol.requestFocus());
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
        Label precice = new Label("Space play/stop   ·   F fade   ·   N sledeca   ·   strelice volume");
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
        grupe.add(new Meni.Grupa("Sakrivene", List.of(Sekcija.sakrivene())));
        grupe.add(new Meni.Grupa("Moja muzika", List.of(
                Sekcija.lokalno("Folderi", Sekcija.Vrsta.LOKALNO),
                Sekcija.lokalno("Dodaj folder...", Sekcija.Vrsta.DODAJ_FOLDER))));
        return grupe;
    }

    // -------------------------------------------------------------- prikaz

    /** Klik u levom meniju: naslovi se postave odmah, stanice stizu iz pozadine. */
    private void otvori(String grupa, Sekcija sekcija) {
        pretraga.clear();
        pretraga.setPromptText("Trazi stanicu...");
        // Space i strelice rade samo kad fokus nije u polju za pretragu, a
        // posle kucanja tamo i ostane - pa se vraca cim se krene dalje.
        skrol.requestFocus();
        vrstaPrikaza = sekcija.vrsta();
        lokalniFilter = sekcija.vrsta() != Sekcija.Vrsta.PRETRAGA;

        // grupe sa jednom stavkom (Omiljene, Sakrivene) ne treba da pisu ime dvaput
        String putanja = grupa.equals(sekcija.naziv()) ? grupa : grupa + " / " + sekcija.naziv();
        mrvica.setText(Tekst.razmaknuto(putanja.toUpperCase()));
        naslov.setText(sekcija.naziv());

        switch (sekcija.vrsta()) {
            case DODAJ_FOLDER -> dodajFolder();
            case LOKALNO, LOKALNE_PESME -> otvoriLokalno();
            default -> ucitaj(() -> switch (sekcija.vrsta()) {
                case OMILJENE -> odeljciOmiljenih();
                case SAKRIVENE -> List.of(Odeljak.bezNaslova(sakrivene.sve()));
                case BEZ_REKLAMA -> api.bezReklama(bezReklama, sekcija.drzava(), limit);
                default -> List.of(Odeljak.bezNaslova(api.pretraga(sekcija, limit)));
            });
        }
    }

    /** Enter u polju za pretragu - ovo ide na API, po imenu stanice. */
    private void traziPoImenu() {
        String upit = pretraga.getText().trim();
        if (upit.isEmpty() || lokalniFilter) {
            return;
        }
        mrvica.setText(Tekst.razmaknuto("PRETRAGA"));
        naslov.setText(upit);
        vrstaPrikaza = Sekcija.Vrsta.PRETRAGA;
        skrol.requestFocus();
        ucitaj(() -> List.of(Odeljak.bezNaslova(api.pretraga(null, null, upit, limit))));
    }

    /**
     * Mreza ide van JavaFX niti; dok traje, stoji "Ucitavam...". Rezultat se
     * vraca na FX nit, jer se tek tada prave kartice.
     */
    private void ucitaj(Supplier<List<Odeljak>> izvor) {
        izvorPrikaza = izvor;
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

    private void nacrtaj(List<Odeljak> ulaz) {
        sadrzaj.getChildren().clear();
        kartice.clear();

        // sakrivene se ne prikazuju nigde osim u svojoj sekciji
        List<Odeljak> odeljci = vrstaPrikaza == Sekcija.Vrsta.SAKRIVENE ? ulaz : bezSakrivenih(ulaz);
        int ukupno = odeljci.stream().mapToInt(o -> o.stanice().size()).sum();
        podnaslov.setText(ukupno + " stanica · klik pusta uzivo"
                + (vrstaPrikaza == Sekcija.Vrsta.OMILJENE ? " · desni klik na karticu za grupu" : ""));

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
                Kartica k = new Kartica(s, omiljene.jeste(s),
                        vrstaPrikaza == Sekcija.Vrsta.SAKRIVENE,
                        this::pusti, this::prebaciOmiljenu, this::prebaciSakrivenu, this::meniGrupa);
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

    /**
     * Omiljene, razvrstane po grupama. Bez ijedne grupe izgleda kao i pre -
     * jedna mreza kartica bez naslova.
     */
    private List<Odeljak> odeljciOmiljenih() {
        List<Stanica> sve = omiljene.sve();
        List<String> imena = grupe.grupe();
        if (imena.isEmpty()) {
            return List.of(Odeljak.bezNaslova(sve));
        }
        List<Odeljak> odeljci = new ArrayList<>();
        for (String ime : imena) {
            List<Stanica> uGrupi = sve.stream().filter(s -> ime.equals(grupe.grupa(s))).toList();
            if (!uGrupi.isEmpty()) {
                odeljci.add(new Odeljak(ime, uGrupi));
            }
        }
        List<Stanica> bezGrupe = sve.stream().filter(s -> grupe.grupa(s).isBlank()).toList();
        if (!bezGrupe.isEmpty()) {
            odeljci.add(new Odeljak("Bez grupe", bezGrupe));
        }
        return odeljci;
    }

    /**
     * Desni klik na karticu u omiljenima: u koju grupu ide. Grupa nastaje tako
     * sto joj se doda prva stanica - nema pravljenja praznih grupa unapred.
     */
    private void meniGrupa(Stanica s) {
        if (vrstaPrikaza != Sekcija.Vrsta.OMILJENE) {
            return;
        }
        Kartica kartica = kartice.get(s.uuid());
        if (kartica == null) {
            return;
        }
        javafx.scene.control.ContextMenu meni = new javafx.scene.control.ContextMenu();
        String trenutna = grupe.grupa(s);
        for (String ime : grupe.grupe()) {
            javafx.scene.control.MenuItem stavka
                    = new javafx.scene.control.MenuItem(ime.equals(trenutna) ? "\u2713  " + ime : "     " + ime);
            stavka.setOnAction(e -> postaviGrupu(s, ime));
            meni.getItems().add(stavka);
        }
        if (!trenutna.isBlank()) {
            javafx.scene.control.MenuItem bez = new javafx.scene.control.MenuItem("     Izvadi iz grupe");
            bez.setOnAction(e -> postaviGrupu(s, ""));
            meni.getItems().add(bez);
        }
        if (!meni.getItems().isEmpty()) {
            meni.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        javafx.scene.control.MenuItem nova = new javafx.scene.control.MenuItem("     Nova grupa...");
        nova.setOnAction(e -> {
            var pitanje = new javafx.scene.control.TextInputDialog();
            pitanje.setTitle("Nova grupa");
            pitanje.setHeaderText("Kako se zove grupa?");
            pitanje.setContentText("Naziv:");
            pitanje.showAndWait().ifPresent(ime -> postaviGrupu(s, ime));
        });
        meni.getItems().add(nova);
        meni.show(kartica, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void postaviGrupu(Stanica s, String ime) {
        grupe.postavi(s, ime);
        ucitaj(izvorPrikaza);
    }

    // ------------------------------------------------------------ moja muzika

    /** Kartice foldera; skeniranje ide van FX niti jer prvi put traje. */
    private void otvoriLokalno() {
        vrstaPrikaza = Sekcija.Vrsta.LOKALNO;
        pretraga.setPromptText("Trazi folder...");
        mrvica.setText(Tekst.razmaknuto("MOJA MUZIKA"));
        naslov.setText("Folderi");
        podnaslov.setText("Skeniram...");
        sadrzaj.getChildren().clear();
        kartice.clear();
        CompletableFuture.supplyAsync(biblioteka::folderi)
                .whenComplete((folderi, greska) -> Platform.runLater(() -> {
                    if (greska != null) {
                        zabelezi("ERROR: " + greska.getMessage());
                        nacrtajFoldere(List.of());
                    } else {
                        nacrtajFoldere(folderi);
                    }
                }));
    }

    private void nacrtajFoldere(List<Folder> folderi) {
        sadrzaj.getChildren().clear();
        kartice.clear();
        podnaslov.setText(folderi.size() + " foldera · klik otvara spisak, dugme na kartici pusta nasumicno");
        FlowPane mreza = new FlowPane(18, 18);
        for (Folder f : folderi) {
            mreza.getChildren().add(new FolderKartica(f, this::pustiFolder, this::otvoriFolder));
        }
        mreza.getChildren().add(FolderKartica.dodavanje(this::dodajFolder));
        sadrzaj.getChildren().add(mreza);
    }

    /** Spisak pesama jednog foldera; pretraga gore filtrira po imenu. */
    private void otvoriFolder(Folder folder) {
        otvoreniFolder = folder;
        vrstaPrikaza = Sekcija.Vrsta.LOKALNE_PESME;
        lokalniFilter = true;
        pretraga.clear();
        pretraga.setPromptText("Trazi pesmu...");
        mrvica.setText(Tekst.razmaknuto(("MOJA MUZIKA / " + folder.naziv()).toUpperCase()));
        naslov.setText(folder.naziv());
        nacrtajPesme(folder, "");
    }

    private void nacrtajPesme(Folder folder, String upit) {
        if (folder == null) {
            return;
        }
        String q = upit == null ? "" : upit.trim().toLowerCase();
        List<Numera> pogodjene = folder.numere().stream()
                .filter(n -> q.isEmpty() || n.opis().toLowerCase().contains(q))
                .toList();

        sadrzaj.getChildren().clear();
        kartice.clear();
        podnaslov.setText(pogodjene.size() + " pesama · klik pusta odabranu");
        if (pogodjene.isEmpty()) {
            sadrzaj.getChildren().add(prazno());
            return;
        }

        VBox tabela = new VBox();
        tabela.getChildren().add(zaglavljeSpiska());
        for (int i = 0; i < pogodjene.size(); i++) {
            tabela.getChildren().add(redSpiska(folder, pogodjene.get(i), i + 1));
        }
        sadrzaj.getChildren().add(tabela);
    }

    private HBox zaglavljeSpiska() {
        HBox red = new HBox(14,
                kolona("#", 44, Pos.CENTER_LEFT),
                kolona("NASLOV", -1, Pos.CENTER_LEFT),
                kolona("IZVODJAC", 280, Pos.CENTER_LEFT),
                kolona("DUZINA", 70, Pos.CENTER_RIGHT),
                kolona("", 110, Pos.CENTER_RIGHT));
        red.getStyleClass().add("spisak-zaglavlje");
        red.setPadding(new Insets(0, 14, 10, 14));
        return red;
    }

    private Label kolona(String tekst, double sirina, Pos poravnanje) {
        Label l = new Label(tekst.isEmpty() ? "" : Tekst.razmaknuto(tekst));
        l.getStyleClass().add("mrvica");
        l.setAlignment(poravnanje);
        if (sirina > 0) {
            l.setMinWidth(sirina);
            l.setPrefWidth(sirina);
        } else {
            l.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(l, Priority.ALWAYS);
        }
        return l;
    }

    /**
     * Jedan red spiska. Pesma koja upravo svira ima zlatan naslov i notu umesto
     * rednog broja; pesma bez taga nudi PREPOZNAJ na kraju reda.
     */
    private HBox redSpiska(Folder folder, Numera n, int broj) {
        boolean svira = tekucaNumera != null && tekucaNumera.putanja().equals(n.putanja());

        Label pokazatelj = new Label(svira ? "\u266A" : String.valueOf(broj));
        pokazatelj.getStyleClass().add(svira ? "spisak-svira" : "spisak-broj");
        pokazatelj.setMinWidth(44);
        pokazatelj.setPrefWidth(44);

        Label naslovPesme = new Label(n.naslov());
        naslovPesme.getStyleClass().add(svira ? "spisak-naslov-svira" : "spisak-naslov");
        naslovPesme.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(naslovPesme, Priority.ALWAYS);

        Label izvodjacPesme = new Label(n.izvodjac().isBlank() ? "nepoznat" : n.izvodjac());
        izvodjacPesme.getStyleClass().add(n.izvodjac().isBlank() ? "spisak-broj" : "spisak-izvodjac");
        izvodjacPesme.setMinWidth(280);
        izvodjacPesme.setPrefWidth(280);

        Label duzina = new Label(n.trajanje());
        duzina.getStyleClass().add("spisak-izvodjac");
        duzina.setMinWidth(70);
        duzina.setPrefWidth(70);
        duzina.setAlignment(Pos.CENTER_RIGHT);

        HBox akcija = new HBox();
        akcija.setMinWidth(110);
        akcija.setPrefWidth(110);
        akcija.setAlignment(Pos.CENTER_RIGHT);
        if (!n.imaTag()) {
            Label dugme = new Label(Tekst.razmaknuto("PREPOZNAJ"));
            dugme.getStyleClass().add("prepoznaj-dugme");
            dugme.setOnMouseClicked(e -> {
                e.consume();
                prepoznajNumeru(folder, n, dugme);
            });
            akcija.getChildren().add(dugme);
        }

        HBox red = new HBox(14, pokazatelj, naslovPesme, izvodjacPesme, duzina, akcija);
        red.getStyleClass().add("spisak-red");
        red.setAlignment(Pos.CENTER_LEFT);
        red.setOnMouseClicked(e -> {
            red(folder).postaviNa(n);
            tekuciRed = red(folder);
            tekuciFolder = folder.naziv();
            pustiNumeru(n);
        });
        return red;
    }

    /**
     * Prepoznavanje pesme bez taga i upis rezultata u sam fajl.
     *
     * Nad fajlom radi i AcoustID, za razliku od radija: otisak se pravi od celog
     * snimka, a njegova baza je gradjena bas od celih snimaka.
     */
    private void prepoznajNumeru(Folder folder, Numera n, Label dugme) {
        dugme.setText(Tekst.razmaknuto("TRAZIM..."));
        dugme.setDisable(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return prepoznavanje.prepoznajFajl(n.putanja());
            } catch (PrepoznajService.Neuspeh e) {
                return e;
            }
        }).thenAccept(ishod -> Platform.runLater(() -> {
            dugme.setDisable(false);
            dugme.setText(Tekst.razmaknuto("PREPOZNAJ"));
            if (!(ishod instanceof Pesma p)) {
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                        ((PrepoznajService.Neuspeh) ishod).getMessage()).show();
                return;
            }
            var pitanje = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Prepoznato:\n\n" + p.izvodjac() + " - " + p.naslov()
                    + "\n\nUpisati u tag fajla?",
                    javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
            pitanje.setHeaderText(null);
            pitanje.showAndWait().ifPresent(odgovor -> {
                if (odgovor != javafx.scene.control.ButtonType.YES) {
                    return;
                }
                try {
                    its.kvizradio.lokalno.Tagovi.upisi(n, p);
                    zabelezi("Tag upisan: " + n.putanja());
                    otvoriFolder(biblioteka.folder(folder.naziv(), folder.putanja()));
                } catch (Exception e) {
                    zabelezi("ERROR: tag nije upisan (" + e.getMessage() + ")");
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                            "Tag nije upisan: " + e.getMessage()).show();
                }
            });
        }));
    }

    private RedSviranja red(Folder folder) {
        return redovi.computeIfAbsent(folder.putanja(), k -> new RedSviranja(folder.numere(), true));
    }

    /** Klik na karticu foldera: nastavlja se tamo gde je taj folder stao. */
    private void pustiFolder(Folder folder) {
        if (folder.numere().isEmpty()) {
            return;
        }
        tekuciRed = red(folder);
        tekuciFolder = folder.naziv();
        Numera n = tekuciRed.trenutna() != null ? tekuciRed.trenutna() : tekuciRed.sledeca();
        if (n != null) {
            pustiNumeru(n);
        }
    }

    private void pustiNumeru(Numera n) {
        otkaziPrepoznavanje();
        skrol.requestFocus();
        tekucaNumera = n;
        izabrana = null;
        bar.lokalniRezim(true);
        bar.ponudiPrepoznavanje(!n.imaTag());
        bar.prikaziLokalnu(n.naslov(), n.izvodjac(), tekuciFolder);
        player.pustiFajl(n.putanja());
        // spisak pokazuje koja pesma ide, pa se posle promene precrtava
        if (vrstaPrikaza == Sekcija.Vrsta.LOKALNE_PESME) {
            nacrtajPesme(otvoreniFolder, pretraga.getText());
        }
    }

    private void sledecaNumera() {
        if (tekuciRed == null) {
            return;
        }
        Numera n = tekuciRed.sledeca();
        if (n != null) {
            pustiNumeru(n);
        }
    }

    private void prethodnaNumera() {
        if (tekuciRed == null) {
            return;
        }
        Numera n = tekuciRed.prethodna();
        if (n != null) {
            pustiNumeru(n);
        }
    }

    /** Dodavanje foldera: izbor sa diska, pa upis u folderi.json. */
    private void dodajFolder() {
        var izbor = new javafx.stage.DirectoryChooser();
        izbor.setTitle("Folder sa muzikom");
        java.io.File odabran = izbor.showDialog(sadrzaj.getScene().getWindow());
        if (odabran == null) {
            otvoriLokalno();
            return;
        }
        biblioteka.dodaj(odabran.getName(), odabran.toPath());
        zabelezi("Dodat folder: " + odabran);
        otvoriLokalno();
    }

    private List<Odeljak> bezSakrivenih(List<Odeljak> odeljci) {
        if (sakrivene.sve().isEmpty()) {
            return odeljci;
        }
        List<Odeljak> izlaz = new ArrayList<>();
        for (Odeljak o : odeljci) {
            List<Stanica> ostale = o.stanice().stream().filter(s -> !sakrivene.jeste(s)).toList();
            if (!ostale.isEmpty()) {
                izlaz.add(new Odeljak(o.naziv(), ostale));
            }
        }
        return izlaz;
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
        otkaziPrepoznavanje();
        skrol.requestFocus();
        bar.lokalniRezim(false);
        bar.ponudiPrepoznavanje(false);
        tekucaNumera = null;
        tekuciRed = null;
        izabrana = s;
        player.pusti(s);
        // brojac klikova je API-ju znak da je stanica ziva; ne sme da drzi UI
        CompletableFuture.runAsync(() -> api.klik(s));
    }

    private void dugmePlayStop() {
        if (player.lokalni()) {
            // lokalna pesma ima kraj, pa dugme pauzira umesto da zaustavlja
            player.pauza(player.stanje() != PlayerService.Stanje.PAUZA);
            return;
        }
        if (player.stanje() != PlayerService.Stanje.STOP) {
            player.stop();
            return;
        }
        Stanica sledeca = izabrana != null ? izabrana : prvaVidljiva();
        if (sledeca != null) {
            pusti(sledeca);
        }
    }

    /** Slajder ili strelice: pomeranje jacine ujedno gasi prigusenje. */
    private void jacina(int procenat) {
        player.jacina(procenat);
        bar.prikaziPrigusenje(player.prigusen());
    }

    /**
     * Prepoznavanje pesme za stanice koje naziv ne salju u metapodacima
     * (mereno: salje ih svaka treca). Ceka se na servis, pa ide van FX niti.
     */
    private void prepoznajPesmu() {
        Stanica sada = player.stanica();
        if (sada == null) {
            return;
        }
        prepoznajemZa = sada;
        bar.prepoznavanjeUToku(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return prepoznavanje.prepoznaj(sada);
            } catch (PrepoznajService.Neuspeh e) {
                return e;
            }
        }).thenAccept(ishod -> Platform.runLater(() -> {
            // stanica se u medjuvremenu promenila - odgovor se odnosi na ono
            // sto se vise ne cuje, pa se tiho odbacuje
            if (prepoznajemZa != sada) {
                return;
            }
            prepoznajemZa = null;
            bar.prepoznavanjeUToku(false);
            if (ishod instanceof Pesma p) {
                zabelezi("Prepoznato: " + p.izvodjac() + " - " + p.naslov());
                player.postaviPesmu(p);
            } else {
                String poruka = ((PrepoznajService.Neuspeh) ishod).getMessage();
                zabelezi("Prepoznavanje: " + poruka);
                // show(), ne showAndWait(): obavestenje ne sme da zamrzne FX nit
                // dok muzika ide - zatvara se kad voditelj stigne
                new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.INFORMATION, poruka).show();
            }
        }));
    }

    /** Promena stanice ili zaustavljanje - osluskivanje vise nema smisla. */
    private void otkaziPrepoznavanje() {
        if (prepoznajemZa == null) {
            return;
        }
        prepoznajemZa = null;
        prepoznavanje.prekini();
        bar.prepoznavanjeUToku(false);
    }

    private void prebaciPrigusenje() {
        player.prigusi(!player.prigusen());
        bar.prikaziPrigusenje(player.prigusen());
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
        if (!sada) {
            // izbacena iz omiljenih - grupa vise nema na sta da se odnosi
            grupe.postavi(s, "");
        }
        Kartica k = kartice.get(s.uuid());
        if (k != null) {
            k.oznaciOmiljenu(sada);
        }
        sidebar.broj("Omiljene", omiljene.sve().size());
    }

    /**
     * Krstic na kartici: iz obicne liste stanica ide u sakrivene, iz sekcije
     * "Sakrivene" se vraca tamo odakle je i dosla - nista se ne pamti, jer su
     * sekcije pretrage API-ja pa se stanica sama vrati na svoje mesto.
     */
    private void prebaciSakrivenu(Stanica s) {
        sakrivene.prebaci(s);
        sidebar.broj("Sakrivene", sakrivene.sve().size());
        if (vrstaPrikaza == Sekcija.Vrsta.SAKRIVENE) {
            ucitaj(izvorPrikaza);
        } else {
            nacrtaj(filtrirano(ucitano, pretraga.getText()));
        }
    }

    private Stanica prvaVidljiva() {
        return kartice.values().stream().findFirst().map(Kartica::stanica).orElse(null);
    }

    private void osveziStanje(PlayerService.Status st) {
        if (st.stanje() == PlayerService.Stanje.STOP) {
            otkaziPrepoznavanje();
        }
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
            case N -> {
                sledecaNumera();
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

    private static List<javafx.scene.image.Image> ikone() {
        List<javafx.scene.image.Image> ikone = new ArrayList<>();
        for (int velicina : new int[]{16, 20, 24, 32, 40, 48, 64, 96, 128, 256}) {
            var ulaz = KvizRadioApp.class.getResourceAsStream("icons/icon-" + velicina + ".png");
            if (ulaz != null) {
                ikone.add(new javafx.scene.image.Image(ulaz));
            }
        }
        return ikone;
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
}
