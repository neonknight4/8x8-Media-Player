package its.kvizradio.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Sat u dnu levog menija. Kviz se vodi po satu - runde imaju pauze, a voditelj
 * gleda u ovaj ekran, ne u traku zadataka.
 */
final class Casovnik {

    private static final DateTimeFormatter OBLIK = DateTimeFormatter.ofPattern("HH:mm");

    private Casovnik() {
    }

    static void pokreni(Label gde) {
        Runnable osvezi = () -> gde.setText(Tekst.razmaknuto(LocalTime.now().format(OBLIK)));
        osvezi.run();
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(20), e -> osvezi.run()));
        t.setCycleCount(Animation.INDEFINITE);
        t.play();
    }
}
