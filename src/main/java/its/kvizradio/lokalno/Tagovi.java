package its.kvizradio.lokalno;

import its.kvizradio.radio.Pesma;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

/** Upis prepoznatog izvodjaca i naslova u sam fajl. */
public final class Tagovi {

    private Tagovi() {
    }

    /**
     * Upisuje izvodjaca i naslov u tag; ako fajl nema tag, pravi ga.
     *
     * Vraca numeru sa novim podacima, da spisak odmah pokaze izmenu bez
     * ponovnog skeniranja - ali kes ostaje stari, pa se pri sledecem skeniranju
     * ionako cita sa diska.
     */
    public static Numera upisi(Numera numera, Pesma pesma) throws Exception {
        AudioFile audio = AudioFileIO.read(numera.putanja().toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ARTIST, pesma.izvodjac());
        tag.setField(FieldKey.TITLE, pesma.naslov());
        AudioFileIO.write(audio);
        return new Numera(numera.putanja(), pesma.izvodjac(), pesma.naslov(),
                numera.album(), numera.trajanjeSek(), true);
    }
}
