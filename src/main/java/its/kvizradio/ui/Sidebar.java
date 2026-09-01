package its.kvizradio.ui;

import its.kvizradio.radio.Meni;
import its.kvizradio.radio.Sekcija;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Levi meni: grupe se otvaraju kao harmonika, otvorena grupa pokazuje svoje
 * stavke i nosi zlatnu crticu. Ne zna nista o mrezi - dobije grupe i javlja
 * koja je stavka kliknuta.
 */
public final class Sidebar extends VBox {

    private final List<Red> redovi = new ArrayList<>();
    private final BiConsumer<String, Sekcija> naIzbor;
    private final VBox spisak = new VBox(4);

    private String aktivnaGrupa;
    private Sekcija aktivnaStavka;

    public Sidebar(BiConsumer<String, Sekcija> naIzbor) {
        this.naIzbor = naIzbor;
        getStyleClass().add("sidebar");
        setPrefWidth(240);
        setMinWidth(240);
        setMaxWidth(240);

        Label logo1 = new Label(Tekst.razmaknuto("KVIZ"));
        logo1.getStyleClass().add("logo");
        Label logo2 = new Label(Tekst.razmaknuto("RADIO"));
        logo2.getStyleClass().add("logo");
        Label potpis = new Label(Tekst.razmaknuto("PUB QUIZ EDITION"));
        potpis.getStyleClass().add("logo-potpis");
        VBox zaglavlje = new VBox(2, logo1, logo2, new Odmak(8), potpis);
        zaglavlje.getStyleClass().add("sidebar-zaglavlje");

        spisak.setPadding(new Insets(18, 0, 24, 0));
        ScrollPane skrol = new ScrollPane(spisak);
        skrol.setFitToWidth(true);
        skrol.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(skrol, Priority.ALWAYS);

        Label sat = new Label();
        sat.getStyleClass().add("sidebar-podnozje");
        Casovnik.pokreni(sat);

        getChildren().addAll(zaglavlje, skrol, sat);
    }

    /** Grupe se postavljaju jednom, pri pokretanju. */
    public void postavi(List<Meni.Grupa> grupe) {
        spisak.getChildren().clear();
        redovi.clear();
        for (Meni.Grupa g : grupe) {
            Red red = new Red(g);
            redovi.add(red);
            spisak.getChildren().add(red);
        }
    }

    /** Broj pored naziva grupe - kod omiljenih se menja u toku rada. */
    public void broj(String grupa, int koliko) {
        for (Red r : redovi) {
            if (r.grupa.naziv().equals(grupa)) {
                r.broj.setText(String.valueOf(koliko));
            }
        }
    }

    public void izaberi(String grupa, Sekcija stavka) {
        aktivnaGrupa = grupa;
        aktivnaStavka = stavka;
        for (Red r : redovi) {
            r.osvezi();
        }
        naIzbor.accept(grupa, stavka);
    }

    /** Prva stavka prve grupe - sa cim se aplikacija otvara. */
    public void izaberiPrvu() {
        if (!redovi.isEmpty() && !redovi.get(0).grupa.stavke().isEmpty()) {
            Meni.Grupa g = redovi.get(0).grupa;
            izaberi(g.naziv(), g.stavke().get(0));
        }
    }

    private final class Red extends VBox {

        private final Meni.Grupa grupa;
        private final Label naziv = new Label();
        private final Label broj = new Label();
        private final Region oznaka = new Region();
        private final VBox stavke = new VBox();

        Red(Meni.Grupa grupa) {
            this.grupa = grupa;
            naziv.setText(grupa.naziv());
            naziv.getStyleClass().add("grupa-naziv");
            broj.setText(String.valueOf(grupa.stavke().size()));
            broj.getStyleClass().add("grupa-broj");

            Region razmak = new Region();
            HBox.setHgrow(razmak, Priority.ALWAYS);
            HBox red = new HBox(8, naziv, razmak, broj);
            red.setAlignment(Pos.CENTER_LEFT);
            red.getStyleClass().add("grupa");

            oznaka.getStyleClass().add("grupa-oznaka");
            oznaka.setPrefWidth(3);
            oznaka.setMaxWidth(3);
            oznaka.setVisible(false);
            StackPane.setAlignment(oznaka, Pos.CENTER_LEFT);
            StackPane.setMargin(oznaka, new Insets(6, 0, 6, 0));

            StackPane zaglavlje = new StackPane(red, oznaka);
            zaglavlje.setOnMouseClicked(e -> {
                // klik na grupu otvara njenu prvu stavku
                if (!grupa.stavke().isEmpty()) {
                    izaberi(grupa.naziv(), grupa.stavke().get(0));
                }
            });

            stavke.setPadding(new Insets(4, 0, 10, 0));
            for (Sekcija s : grupa.stavke()) {
                Label l = new Label(s.naziv());
                l.getStyleClass().add("stavka");
                l.setMaxWidth(Double.MAX_VALUE);
                l.setOnMouseClicked(e -> {
                    e.consume();
                    izaberi(grupa.naziv(), s);
                });
                stavke.getChildren().add(l);
            }

            getChildren().addAll(zaglavlje, stavke);
            osvezi();
        }

        void osvezi() {
            boolean aktivna = grupa.naziv().equals(aktivnaGrupa);
            oznaka.setVisible(aktivna);
            // grupa sa jednom stavkom (Omiljene) nema sta da otvori
            boolean imaSpisak = aktivna && grupa.stavke().size() > 1;
            stavke.setVisible(imaSpisak);
            stavke.setManaged(imaSpisak);
            postaviKlasu(naziv, "aktivna", aktivna);
            postaviKlasu(broj, "aktivna", aktivna);

            for (int i = 0; i < stavke.getChildren().size(); i++) {
                Label l = (Label) stavke.getChildren().get(i);
                postaviKlasu(l, "aktivna", grupa.stavke().get(i).equals(aktivnaStavka));
            }
        }
    }

    static void postaviKlasu(javafx.scene.Node n, String klasa, boolean ima) {
        n.getStyleClass().remove(klasa);
        if (ima) {
            n.getStyleClass().add(klasa);
        }
    }

    /** Prazan razmak zadate visine - VBox spacing ne razlikuje logo od potpisa. */
    private static final class Odmak extends Region {
        Odmak(double visina) {
            setMinHeight(visina);
            setPrefHeight(visina);
        }
    }
}
