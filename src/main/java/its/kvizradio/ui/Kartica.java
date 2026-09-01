package its.kvizradio.ui;

import its.kvizradio.radio.Stanica;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Kartica jedne stanice: logo (favicon, a dok ga nema inicijali), ime, bitrate,
 * zvezdica za omiljene. Klik bilo gde pusta stanicu.
 *
 * Favicon se ucitava u pozadini ({@code backgroundLoading}) - lista ume da ima
 * cetrdeset stanica, a dosta favicona su mrtvi linkovi ili .ico koji JavaFX ne
 * cita. Zato inicijali stoje odmah, a slika se pojavi tek ako stigne ispravna.
 */
public final class Kartica extends StackPane {

    private static final double PRECNIK = 62;

    private final Stanica stanica;
    private final Label zvezda;
    private final HBox ekvilajzer;
    private Timeline pulsiranje;

    public Kartica(Stanica stanica, boolean omiljena,
            Consumer<Stanica> naPustanje, Consumer<Stanica> naZvezdu) {

        this.stanica = stanica;
        getStyleClass().add("kartica");
        // fiksna visina, inace red kartica sa dugackim imenom razvuce ceo red
        setPrefSize(212, 190);
        setMinSize(212, 190);
        setMaxSize(212, 190);

        Label ime = new Label(stanica.ime());
        ime.getStyleClass().add("kartica-ime");
        ime.setWrapText(true);
        ime.setAlignment(Pos.CENTER);
        ime.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        ime.setMaxWidth(Double.MAX_VALUE);

        Label meta = new Label(meta(stanica));
        meta.getStyleClass().add("kartica-meta");

        VBox sadrzaj = new VBox(12, logo(stanica), ime, meta);
        sadrzaj.setAlignment(Pos.TOP_CENTER);

        zvezda = new Label("★");
        zvezda.getStyleClass().add("zvezda");
        zvezda.setOnMouseClicked(e -> {
            // klik na zvezdicu ne sme da pusti stanicu
            e.consume();
            naZvezdu.accept(stanica);
        });
        oznaciOmiljenu(omiljena);
        StackPane.setAlignment(zvezda, Pos.TOP_RIGHT);
        StackPane.setMargin(zvezda, new Insets(6, 8, 0, 0));

        ekvilajzer = ekvilajzer();
        ekvilajzer.setVisible(false);
        StackPane.setAlignment(ekvilajzer, Pos.BOTTOM_LEFT);
        StackPane.setMargin(ekvilajzer, new Insets(0, 0, 12, 14));

        getChildren().addAll(sadrzaj, zvezda, ekvilajzer);
        setOnMouseClicked(e -> naPustanje.accept(stanica));
    }

    public Stanica stanica() {
        return stanica;
    }

    public void oznaciOmiljenu(boolean omiljena) {
        zvezda.getStyleClass().remove("omiljena");
        if (omiljena) {
            zvezda.getStyleClass().add("omiljena");
        }
    }

    /**
     * Kartica stanice koja je izabrana dobija zlatni okvir koji pulsira, a kad
     * stvarno svira i ekvilajzer - da se na projektoru iz drugog kraja sale vidi
     * sta je pusteno.
     */
    public void oznaciAktivnu(boolean aktivna, boolean svira) {
        getStyleClass().remove("svira");
        if (aktivna) {
            getStyleClass().add("svira");
        }
        pulsiraj(aktivna);
        ekvilajzer.setVisible(svira);
        for (javafx.scene.Node n : ekvilajzer.getChildren()) {
            Timeline t = (Timeline) n.getUserData();
            if (svira) {
                t.play();
            } else {
                t.stop();
            }
        }
    }

    private void pulsiraj(boolean upaljeno) {
        if (pulsiranje != null) {
            pulsiranje.stop();
            pulsiranje = null;
            setEffect(null);
        }
        if (!upaljeno) {
            return;
        }
        DropShadow sjaj = new DropShadow(18, Color.web("#D4AF37", 0.10));
        setEffect(sjaj);
        pulsiranje = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(sjaj.radiusProperty(), 18),
                        new KeyValue(sjaj.colorProperty(), Color.web("#D4AF37", 0.10))),
                new KeyFrame(Duration.millis(1200),
                        new KeyValue(sjaj.radiusProperty(), 30),
                        new KeyValue(sjaj.colorProperty(), Color.web("#D4AF37", 0.28))));
        pulsiranje.setAutoReverse(true);
        pulsiranje.setCycleCount(Animation.INDEFINITE);
        pulsiranje.play();
    }

    private static HBox ekvilajzer() {
        HBox box = new HBox(3);
        box.setAlignment(Pos.BOTTOM_LEFT);
        box.setPrefHeight(14);
        for (int i = 0; i < 3; i++) {
            Rectangle r = new Rectangle(3, 14, Color.web("#D4AF37"));
            // skaliranje ide od dna (u dizajnu transform-origin: bottom), pa
            // ide preko Scale transformacije - setScaleY bi sirio na obe strane
            Scale skala = new Scale(1, 1, 1.5, 14);
            r.getTransforms().add(skala);
            Timeline t = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(skala.yProperty(), 0.25, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(360), new KeyValue(skala.yProperty(), 1, Interpolator.EASE_BOTH)));
            t.setAutoReverse(true);
            t.setCycleCount(Animation.INDEFINITE);
            t.setDelay(Duration.millis(240L * i));
            r.setUserData(t);
            box.getChildren().add(r);
        }
        return box;
    }

    private static StackPane logo(Stanica stanica) {
        Circle krug = new Circle(PRECNIK / 2);
        krug.setFill(Color.web("#0E0E0E"));
        krug.setStroke(Color.web("#D4AF37", 0.35));

        Label inicijali = new Label(Tekst.inicijali(stanica.ime()));
        inicijali.getStyleClass().add("inicijali");

        StackPane p = new StackPane(krug, inicijali);
        p.setPrefSize(PRECNIK, PRECNIK);
        p.setMinSize(PRECNIK, PRECNIK);
        p.setMaxSize(PRECNIK, PRECNIK);

        String favicon = stanica.favicon();
        if (favicon != null && favicon.startsWith("http")) {
            Image slika = new Image(favicon, PRECNIK, PRECNIK, true, true, true);
            slika.progressProperty().addListener((o, staro, novo) -> {
                if (novo.doubleValue() >= 1.0 && !slika.isError() && slika.getWidth() > 0) {
                    ImageView pogled = new ImageView(slika);
                    pogled.setFitWidth(PRECNIK - 4);
                    pogled.setFitHeight(PRECNIK - 4);
                    pogled.setPreserveRatio(true);
                    pogled.setClip(new Circle((PRECNIK - 4) / 2, (PRECNIK - 4) / 2, (PRECNIK - 4) / 2));
                    p.getChildren().set(1, pogled);
                }
            });
        }
        return p;
    }

    private static String meta(Stanica s) {
        String drzava = s.drzava().isBlank() ? "" : s.drzava().toUpperCase() + " · ";
        return s.bitrate() > 0 ? drzava + s.bitrate() + " kbps" : drzava + s.kodek();
    }
}
