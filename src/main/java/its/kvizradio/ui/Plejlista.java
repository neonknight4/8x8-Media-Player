package its.kvizradio.ui;

import its.kvizradio.lokalno.Numera;
import its.kvizradio.lokalno.RedSviranja;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Desna strana: red sviranja sa diska.
 *
 * Panel samo crta i javlja sta je kliknuto - red drzi i menja
 * {@link RedSviranja}, pa se posle svake izmene panel iscrta ponovo. Redovi se
 * prevlace misem; prevuceni red nosi svoj broj u dragboardu.
 */
public final class Plejlista extends VBox {

    private static final String TIP = "kvizradio-red";

    private final IntConsumer naIzbor;
    private final BiConsumer<Integer, Integer> naPomeranje;
    private final IntConsumer naIzbacivanje;
    private final Consumer<Boolean> naNasumicno;
    private final Runnable naPraznjenje;

    private final Label izvor = new Label();
    private final Label broj = new Label();
    private final Label nasumicno = new Label();
    private final Label ocisti = new Label(Tekst.razmaknuto("OCISTI"));
    private final VBox redovi = new VBox();

    public Plejlista(IntConsumer naIzbor, BiConsumer<Integer, Integer> naPomeranje,
            IntConsumer naIzbacivanje, Consumer<Boolean> naNasumicno, Runnable naPraznjenje) {
        this.naIzbor = naIzbor;
        this.naPomeranje = naPomeranje;
        this.naIzbacivanje = naIzbacivanje;
        this.naNasumicno = naNasumicno;
        this.naPraznjenje = naPraznjenje;

        getStyleClass().add("plejlista");
        setPrefWidth(320);
        setMinWidth(320);
        setMaxWidth(320);

        Label naslov = new Label(Tekst.razmaknuto("RED SVIRANJA"));
        naslov.getStyleClass().add("plejlista-naslov");
        izvor.getStyleClass().add("plejlista-izvor");
        broj.getStyleClass().add("mrvica");

        nasumicno.getStyleClass().add("prepoznaj-dugme");
        nasumicno.setTooltip(new Tooltip("Nasumicno ili redom"));
        ocisti.getStyleClass().add("prepoznaj-dugme");
        ocisti.setTooltip(new Tooltip("Isprazni red i zaustavi"));
        ocisti.setOnMouseClicked(e -> naPraznjenje.run());

        HBox dugmad = new HBox(8, nasumicno, ocisti);
        dugmad.setAlignment(Pos.CENTER_LEFT);

        VBox zaglavlje = new VBox(6, naslov, izvor, broj, dugmad);
        zaglavlje.getStyleClass().add("plejlista-zaglavlje");

        redovi.setPadding(new Insets(6, 0, 20, 0));
        ScrollPane skrol = new ScrollPane(redovi);
        skrol.setFitToWidth(true);
        skrol.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        skrol.getStyleClass().add("mreza");
        VBox.setVgrow(skrol, Priority.ALWAYS);

        getChildren().addAll(zaglavlje, skrol);
        prikazi(false);
    }

    /** Red se promenio (ili ga vise nema) - panel se iscrtava iznova. */
    public void osvezi(String folder, RedSviranja red) {
        if (red == null || red.prazan()) {
            prikazi(false);
            redovi.getChildren().clear();
            return;
        }
        prikazi(true);
        izvor.setText(folder == null ? "" : folder);
        broj.setText(Tekst.razmaknuto((red.velicina() + " PESAMA")));
        nasumicno.setText(Tekst.razmaknuto(red.nasumicno() ? "NASUMICNO" : "REDOM"));
        Sidebar.postaviKlasu(nasumicno, "ukljuceno", red.nasumicno());
        boolean sada = red.nasumicno();
        nasumicno.setOnMouseClicked(e -> naNasumicno.accept(!sada));

        redovi.getChildren().clear();
        int mesto = red.mesto();
        for (int i = 0; i < red.velicina(); i++) {
            redovi.getChildren().add(red(red.spisak().get(i), i, i == mesto));
        }
    }

    private void prikazi(boolean da) {
        setVisible(da);
        setManaged(da);
    }

    private HBox red(Numera n, int index, boolean svira) {
        Label pokazatelj = new Label(svira ? "♪" : String.valueOf(index + 1));
        pokazatelj.getStyleClass().add(svira ? "spisak-svira" : "spisak-broj");
        pokazatelj.setMinWidth(26);
        pokazatelj.setPrefWidth(26);

        Label naslov = new Label(n.naslov());
        naslov.getStyleClass().add(svira ? "plejlista-naslov-svira" : "plejlista-red-naslov");
        naslov.setMaxWidth(Double.MAX_VALUE);
        Label izvodjac = new Label(n.izvodjac().isBlank() ? n.trajanje()
                : n.izvodjac() + (n.trajanje().isEmpty() ? "" : "  ·  " + n.trajanje()));
        izvodjac.getStyleClass().add("plejlista-red-meta");

        VBox tekst = new VBox(1, naslov, izvodjac);
        tekst.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tekst, Priority.ALWAYS);

        Label izbaci = new Label("✕");
        izbaci.getStyleClass().add("sakrij");
        izbaci.setTooltip(new Tooltip("Izbaci iz reda"));
        izbaci.setOnMouseClicked(e -> {
            e.consume();
            naIzbacivanje.accept(index);
        });

        HBox red = new HBox(10, pokazatelj, tekst, izbaci);
        red.getStyleClass().add("plejlista-red");
        Sidebar.postaviKlasu(red, "svira", svira);
        red.setAlignment(Pos.CENTER_LEFT);
        red.setOnMouseClicked(e -> naIzbor.accept(index));
        prevlacenje(red, index);
        return red;
    }

    /** Prevlacenje reda: nosi se samo broj, ostalo radi {@link RedSviranja}. */
    private void prevlacenje(HBox red, int index) {
        red.setOnDragDetected(e -> {
            Dragboard db = red.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent sadrzaj = new ClipboardContent();
            sadrzaj.putString(TIP + ":" + index);
            db.setContent(sadrzaj);
            db.setDragView(red.snapshot(null, null));
            e.consume();
        });
        red.setOnDragOver(e -> {
            if (od(e.getDragboard()) >= 0) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        red.setOnDragEntered(e -> Sidebar.postaviKlasu(red, "cilj", od(e.getDragboard()) >= 0));
        red.setOnDragExited(e -> Sidebar.postaviKlasu(red, "cilj", false));
        red.setOnDragDropped(e -> {
            int od = od(e.getDragboard());
            boolean uspelo = od >= 0 && od != index;
            if (uspelo) {
                naPomeranje.accept(od, index);
            }
            e.setDropCompleted(uspelo);
            e.consume();
        });
    }

    /** Odakle je red krenuo; -1 znaci da se prevlaci nesto tudje. */
    private static int od(Dragboard db) {
        String s = db.getString();
        if (s == null || !s.startsWith(TIP + ":")) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(TIP.length() + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
