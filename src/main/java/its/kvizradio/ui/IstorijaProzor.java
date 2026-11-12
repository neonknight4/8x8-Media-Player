package its.kvizradio.ui;

import its.kvizradio.radio.IstorijaPesama;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Prozor sa pesmama koje su isle na traci, najnovija gore, po danima.
 *
 * Ne blokira glavni prozor - muzika i precice rade dok je otvoren, a nova
 * pesma se doda cim se upise. Ceo fajl se otvara u sistemskom editoru.
 */
public final class IstorijaProzor {

    /** Dovoljno za vise kviz-veceri; ostatak je u fajlu. */
    private static final int NAJVISE = 500;
    private static final DateTimeFormatter SAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAN = DateTimeFormatter.ofPattern("d.M.yyyy");

    private final IstorijaPesama istorija;
    private final Window vlasnik;
    private final Runnable naOtvaranjeFajla;
    private final VBox redovi = new VBox();
    private final Label otvoriFajl = new Label(Tekst.razmaknuto("OTVORI FAJL"));
    private Stage prozor;

    public IstorijaProzor(IstorijaPesama istorija, Window vlasnik, Runnable naOtvaranjeFajla) {
        this.istorija = istorija;
        this.vlasnik = vlasnik;
        this.naOtvaranjeFajla = naOtvaranjeFajla;
    }

    public void prikazi() {
        if (prozor == null) {
            prozor = napravi();
        }
        nacrtaj();
        prozor.show();
        prozor.toFront();
    }

    /** Upisana je nova pesma - vidi se odmah ako je prozor otvoren. */
    public void osvezi() {
        if (prozor != null && prozor.isShowing()) {
            nacrtaj();
        }
    }

    public void zatvori() {
        if (prozor != null) {
            prozor.hide();
        }
    }

    private Stage napravi() {
        Label naslov = new Label(Tekst.razmaknuto("SLUŠANE PESME"));
        naslov.getStyleClass().add("plejlista-naslov");

        otvoriFajl.getStyleClass().add("prepoznaj-dugme");
        otvoriFajl.setTooltip(new Tooltip(istorija.fajl().toString()));
        otvoriFajl.setOnMouseClicked(e -> {
            if (!otvoriFajl.isDisable()) {
                naOtvaranjeFajla.run();
            }
        });

        Region razmak = new Region();
        HBox.setHgrow(razmak, Priority.ALWAYS);
        HBox zaglavlje = new HBox(naslov, razmak, otvoriFajl);
        zaglavlje.setAlignment(Pos.CENTER_LEFT);
        zaglavlje.getStyleClass().add("plejlista-zaglavlje");

        redovi.setPadding(new Insets(6, 0, 20, 0));
        ScrollPane skrol = new ScrollPane(redovi);
        skrol.setFitToWidth(true);
        skrol.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(skrol, Priority.ALWAYS);

        VBox koren = new VBox(zaglavlje, skrol);
        koren.getStyleClass().add("plejlista");

        Scene scena = new Scene(koren, 480, 600, Color.web("#08090D"));
        scena.getStylesheets().addAll(vlasnik.getScene().getStylesheets());
        scena.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                prozor.hide();
            }
        });

        Stage s = new Stage();
        s.initOwner(vlasnik);
        s.setTitle("Slušane pesme");
        if (vlasnik instanceof Stage glavni) {
            s.getIcons().setAll(glavni.getIcons());
        }
        s.setScene(scena);
        return s;
    }

    private void nacrtaj() {
        redovi.getChildren().clear();
        otvoriFajl.setDisable(!Files.isRegularFile(istorija.fajl()));
        List<IstorijaPesama.Zapis> zapisi = istorija.poslednji(NAJVISE);
        if (zapisi.isEmpty()) {
            Label prazno = new Label("Još nema zapisanih pesama.\n"
                    + "Pesma se upiše čim se pojavi na traci - iz strima ili prepoznata.");
            prazno.getStyleClass().add("plejlista-red-meta");
            prazno.setWrapText(true);
            prazno.setPadding(new Insets(14, 22, 0, 22));
            redovi.getChildren().add(prazno);
            return;
        }
        LocalDate dan = null;
        for (IstorijaPesama.Zapis z : zapisi) {
            LocalDate d = z.vreme().toLocalDate();
            if (!d.equals(dan)) {
                dan = d;
                redovi.getChildren().add(dan(d));
            }
            redovi.getChildren().add(red(z));
        }
    }

    private static Label dan(LocalDate d) {
        String tekst = d.equals(LocalDate.now()) ? "DANAS" : d.format(DAN);
        Label l = new Label(Tekst.razmaknuto(tekst));
        l.getStyleClass().add("istorija-dan");
        return l;
    }

    private static HBox red(IstorijaPesama.Zapis z) {
        Label sat = new Label(z.vreme().format(SAT));
        sat.getStyleClass().add("istorija-sat");
        sat.setMinWidth(Region.USE_PREF_SIZE);

        Label pesma = new Label(z.pesma());
        pesma.getStyleClass().add("plejlista-red-naslov");
        pesma.setWrapText(true);
        Label stanica = new Label(z.stanica());
        stanica.getStyleClass().add("plejlista-red-meta");

        VBox tekst = new VBox(2, pesma, stanica);
        HBox red = new HBox(14, sat, tekst);
        red.setAlignment(Pos.TOP_LEFT);
        red.getStyleClass().add("istorija-red");
        return red;
    }
}
