package its.kvizradio.ui;

import its.kvizradio.player.PlayerService;
import its.kvizradio.radio.Stanica;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Donji bar: sta svira, veliko dugme, jacina, fade out.
 *
 * Dugme i FADE su jedina dva mesta koja voditelj dira usred kviza, pa su
 * najveci elementi na ekranu i imaju precice (Space, F).
 */
public final class PlayerBar extends HBox {

    private final Label inicijali = new Label("—");
    private final Circle prsten = new Circle(23);
    private final Label ime = new Label("Nijedna stanica");
    private final Label status = new Label();
    private final Label meta = new Label();
    private final Slider jacina = new Slider(0, 100, 70);
    private final Label jacinaBroj = new Label("70");
    private final Label fade = new Label(Tekst.razmaknuto("FADE OUT"));
    private final Polygon trougao = new Polygon(0, 0, 0, 24, 20, 12);
    private final Rectangle kvadrat = new Rectangle(17, 17);
    private final FadeTransition treperenje;
    private final Polygon zvucnik = new Polygon(0, 5, 4, 5, 9, 0, 9, 16, 4, 11, 0, 11);
    private final Arc talasBlizi = new Arc(10, 8, 5, 5, -45, 90);
    private final Arc talasDalji = new Arc(10, 8, 8, 8, -45, 90);
    private final Line precrtano = new Line(1, 15, 17, 1);

    public PlayerBar(Runnable naDugme, Runnable naFade, Consumer<Integer> naJacinu, Runnable naMute) {
        getStyleClass().add("player-bar");
        setPrefHeight(90);
        setMinHeight(90);
        setAlignment(Pos.CENTER);
        setSpacing(28);

        getChildren().addAll(sada(), veliko(naDugme), desno(naFade, naMute));

        jacina.valueProperty().addListener((o, staro, novo) -> {
            int v = (int) Math.round(novo.doubleValue());
            jacinaBroj.setText(String.valueOf(v));
            naJacinu.accept(v);
        });

        treperenje = new FadeTransition(Duration.millis(550), status);
        treperenje.setFromValue(1.0);
        treperenje.setToValue(0.35);
        treperenje.setAutoReverse(true);
        treperenje.setCycleCount(Animation.INDEFINITE);

        prikazi(new PlayerService.Status(PlayerService.Stanje.STOP, null, ""));
    }

    public void postaviJacinu(int v) {
        jacina.setValue(v);
    }

    /** Ikonica pored VOL: precrtan zvucnik kad je zvuk prigusen. */
    public void prikaziPrigusenje(boolean prigusen) {
        zvucnik.setFill(Color.web(prigusen ? "#57544B" : "#D4AF37"));
        talasBlizi.setVisible(!prigusen);
        talasDalji.setVisible(!prigusen);
        precrtano.setVisible(prigusen);
    }

    public int jacina() {
        return (int) Math.round(jacina.getValue());
    }

    /** Stanica koja je izabrana ali jos ne svira - da bar ne bude prazan po pokretanju. */
    public void pripremi(Stanica s) {
        if (s != null) {
            postaviStanicu(s, false);
        }
    }

    public void prikazi(PlayerService.Status st) {
        boolean radi = st.stanje() != PlayerService.Stanje.STOP;
        postaviStanicu(st.stanica(), radi);

        String tekst;
        String klasa;
        boolean pulsira;
        switch (st.stanje()) {
            case SVIRA -> {
                tekst = "PLAYING";
                klasa = "svira";
                pulsira = false;
            }
            case POVEZIVANJE -> {
                tekst = "BUFFERING";
                klasa = "radi";
                pulsira = true;
            }
            case GRESKA -> {
                tekst = "PONOVO SE POVEZUJEM";
                klasa = "radi";
                pulsira = true;
            }
            default -> {
                tekst = "ZAUSTAVLJENO";
                klasa = null;
                pulsira = false;
            }
        }
        status.setText(Tekst.razmaknuto(tekst));
        Sidebar.postaviKlasu(status, "svira", "svira".equals(klasa));
        Sidebar.postaviKlasu(status, "radi", "radi".equals(klasa));
        if (pulsira) {
            treperenje.play();
        } else {
            treperenje.stop();
            status.setOpacity(1);
        }

        trougao.setVisible(!radi);
        kvadrat.setVisible(radi);
        fade.setDisable(!radi);
        Sidebar.postaviKlasu(fade, "u-toku", false);
    }

    /** Fade je u toku - dugme to pokazuje dok jacina pada. */
    public void oznaciFade() {
        fade.setText(Tekst.razmaknuto("FADING..."));
        Sidebar.postaviKlasu(fade, "u-toku", true);
    }

    private void postaviStanicu(Stanica s, boolean radi) {
        boolean ima = s != null;
        ime.setText(ima ? s.ime() : "Nijedna stanica");
        Sidebar.postaviKlasu(ime, "prazno", !ima);
        inicijali.setText(ima ? Tekst.inicijali(s.ime()) : "—");
        inicijali.setStyle(ima ? "-fx-text-fill: #D4AF37;" : "-fx-text-fill: #3A3833;");
        prsten.setStroke(Color.web(ima ? "#D4AF37" : "#242424", ima ? 0.45 : 1));
        meta.setText(ima ? opis(s) : "");
        if (!radi && ima) {
            fade.setText(Tekst.razmaknuto("FADE OUT"));
        }
    }

    private static String opis(Stanica s) {
        String d = s.drzava().isBlank() ? "" : s.drzava().toUpperCase() + " · ";
        return s.bitrate() > 0 ? d + s.bitrate() + " kbps" : d + s.kodek();
    }

    private HBox sada() {
        prsten.setFill(Color.web("#101010"));
        inicijali.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        StackPane avatar = new StackPane(prsten, inicijali);

        ime.getStyleClass().add("sada-ime");
        ime.setMaxWidth(210);
        status.getStyleClass().add("status");
        meta.getStyleClass().add("sada-meta");

        HBox donji = new HBox(8, status, meta);
        donji.setAlignment(Pos.CENTER_LEFT);

        VBox tekst = new VBox(5, ime, donji);
        tekst.setAlignment(Pos.CENTER_LEFT);

        HBox box = new HBox(14, avatar, tekst);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPrefWidth(300);
        box.setMinWidth(300);
        return box;
    }

    private HBox veliko(Runnable naDugme) {
        Circle krug = new Circle(31, Color.web("#D4AF37"));
        krug.setEffect(new DropShadow(26, Color.web("#D4AF37", 0.28)));

        trougao.setFill(Color.web("#0A0A0A"));
        trougao.setTranslateX(3);
        kvadrat.setFill(Color.web("#0A0A0A"));
        kvadrat.setArcWidth(4);
        kvadrat.setArcHeight(4);
        kvadrat.setVisible(false);

        StackPane dugme = new StackPane(krug, trougao, kvadrat);
        dugme.getStyleClass().add("veliko-dugme");
        dugme.setOnMouseClicked(e -> naDugme.run());
        dugme.setOnMouseEntered(e -> krug.setFill(Color.web("#F0D060")));
        dugme.setOnMouseExited(e -> krug.setFill(Color.web("#D4AF37")));

        HBox box = new HBox(dugme);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox desno(Runnable naFade, Runnable naMute) {
        Label oznaka = new Label(Tekst.razmaknuto("VOL"));
        oznaka.getStyleClass().add("oznaka");

        jacina.setPrefWidth(132);
        jacina.setMinWidth(132);
        jacinaBroj.getStyleClass().add("jacina-broj");
        jacinaBroj.setMinWidth(30);

        HBox vol = new HBox(12, mute(naMute), oznaka, jacina, jacinaBroj);
        vol.setAlignment(Pos.CENTER);

        fade.getStyleClass().add("fade-dugme");
        fade.setOnMouseClicked(e -> {
            if (!fade.isDisable()) {
                naFade.run();
            }
        });

        HBox box = new HBox(26, vol, fade);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /**
     * Dugme za prigusenje. Zvucnik je nacrtan, ne emoji - emoji zvucnika nema u
     * svakom fontu, a bar mora da izgleda isto i na Windowsu i u razvoju.
     */
    private StackPane mute(Runnable naMute) {
        zvucnik.setFill(Color.web("#D4AF37"));
        for (Arc talas : new Arc[]{talasBlizi, talasDalji}) {
            talas.setType(ArcType.OPEN);
            talas.setFill(null);
            talas.setStroke(Color.web("#D4AF37"));
            talas.setStrokeWidth(1.6);
        }
        precrtano.setStroke(Color.web("#D4AF37"));
        precrtano.setStrokeWidth(1.8);
        precrtano.setVisible(false);

        StackPane dugme = new StackPane(new Group(zvucnik, talasBlizi, talasDalji, precrtano));
        dugme.setPrefSize(26, 22);
        dugme.setMinSize(26, 22);
        dugme.getStyleClass().add("mute-dugme");
        Tooltip.install(dugme, new Tooltip("Prigusi zvuk"));
        dugme.setOnMouseClicked(e -> naMute.run());
        return dugme;
    }
}
