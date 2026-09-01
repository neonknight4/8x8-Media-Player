package its.kvizradio.ui;

import its.kvizradio.lokalno.Folder;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

import java.util.function.Consumer;

/**
 * Kartica jednog foldera: ikonica foldera, naziv, broj pesama.
 *
 * Klik otvara spisak pesama, a zlatno dugme koje se pojavi preko kartice pusta
 * folder nasumicno. Prva verzija je imala klik = pusti i dupli klik = otvori,
 * pa je jedan klik morao da ceka petinu sekunde da vidi hoce li stici drugi -
 * ovako nista ne ceka.
 */
public final class FolderKartica extends StackPane {

    public FolderKartica(Folder folder, Consumer<Folder> naPustanje, Consumer<Folder> naOtvaranje) {
        getStyleClass().add("kartica");
        setPrefSize(212, 190);
        setMinSize(212, 190);
        setMaxSize(212, 190);

        Label naziv = new Label(folder.naziv());
        naziv.getStyleClass().add("kartica-ime");
        naziv.setWrapText(true);
        naziv.setAlignment(Pos.CENTER);
        naziv.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        naziv.setMaxWidth(Double.MAX_VALUE);

        Label koliko = new Label(folder.postoji()
                ? Tekst.razmaknuto((folder.numere().size() + " PESAMA"))
                : Tekst.razmaknuto("FOLDER NE POSTOJI"));
        koliko.getStyleClass().add("kartica-meta");

        VBox sadrzaj = new VBox(12, ikonicaFoldera(), naziv, koliko);
        sadrzaj.setAlignment(Pos.TOP_CENTER);

        StackPane preklop = preklopSaPustanjem();
        getChildren().addAll(sadrzaj, preklop);

        setOnMouseClicked(e -> naOtvaranje.accept(folder));
        preklop.setOnMouseClicked(e -> {
            e.consume();
            naPustanje.accept(folder);
        });
        setOnMouseEntered(e -> preklop.setVisible(folder.postoji()));
        setOnMouseExited(e -> preklop.setVisible(false));
    }

    /** Kruzic sa nacrtanim folderom - isti okvir kao logo stanice. */
    private static StackPane ikonicaFoldera() {
        Circle krug = new Circle(31);
        krug.setFill(Color.web("#0E0E0E"));
        krug.setStroke(Color.web("#D4AF37", 0.35));

        Rectangle telo = new Rectangle(26, 19);
        telo.setFill(null);
        telo.setStroke(Color.web("#D4AF37"));
        telo.setStrokeWidth(1.5);
        telo.setArcWidth(4);
        telo.setArcHeight(4);

        Rectangle jezicak = new Rectangle(11, 5);
        jezicak.setFill(null);
        jezicak.setStroke(Color.web("#D4AF37"));
        jezicak.setStrokeWidth(1.5);
        jezicak.setTranslateX(-7.5);
        jezicak.setTranslateY(-12);

        StackPane ikonica = new StackPane(krug, telo, jezicak);
        ikonica.setPrefSize(62, 62);
        ikonica.setMaxSize(62, 62);
        return ikonica;
    }

    /** Zlatno dugme preko cele kartice; vidi se tek kad je mis nad karticom. */
    private static StackPane preklopSaPustanjem() {
        Circle krug = new Circle(26, Color.web("#D4AF37"));
        krug.setEffect(new javafx.scene.effect.DropShadow(22, Color.web("#D4AF37", 0.35)));
        Polygon trougao = new Polygon(0, 0, 0, 20, 16, 10);
        trougao.setFill(Color.web("#0A0A0A"));
        trougao.setTranslateX(3);

        StackPane preklop = new StackPane(new StackPane(krug, trougao));
        preklop.setStyle("-fx-background-color: rgba(8,8,8,0.72); -fx-background-radius: 14;");
        preklop.setAlignment(Pos.CENTER);
        preklop.setVisible(false);
        preklop.setCursor(javafx.scene.Cursor.HAND);
        return preklop;
    }

    /** Isprekidana kartica na kraju mreze: dodavanje novog foldera. */
    public static StackPane dodavanje(Runnable naDodavanje) {
        Circle krug = new Circle(31);
        krug.setFill(null);
        krug.setStroke(Color.web("#D4AF37", 0.55));
        krug.getStrokeDashArray().addAll(4.0, 4.0);
        Label plus = new Label("+");
        plus.setStyle("-fx-font-size: 28px; -fx-text-fill: #D4AF37;");

        Label natpis = new Label("Dodaj folder");
        natpis.setStyle("-fx-font-size: 14px; -fx-text-fill: #D4AF37;");

        VBox sadrzaj = new VBox(12, new StackPane(krug, plus), natpis);
        sadrzaj.setAlignment(Pos.CENTER);

        StackPane kartica = new StackPane(sadrzaj);
        kartica.getStyleClass().add("kartica-dodavanje");
        kartica.setPrefSize(212, 190);
        kartica.setMinSize(212, 190);
        kartica.setMaxSize(212, 190);
        kartica.setPadding(new Insets(24, 18, 20, 18));
        kartica.setOnMouseClicked(e -> naDodavanje.run());
        return kartica;
    }

    /** Prazan prostor iste visine - da mreza ostane poravnata. */
    static Region razmak(double sirina) {
        Region r = new Region();
        r.setPrefWidth(sirina);
        return r;
    }
}
