package its.kvizradio.player;

import its.kvizradio.radio.Stanica;

import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sviranje strima preko libvlc-a (vlcj), bez video izlaza.
 *
 * Nema nijednog JavaFX poziva - klasa se koristi i iz CLI testa. Obavestenja o
 * stanju stizu sa vlcj-jeve niti dogadjaja, pa ih UI mora prepakovati u
 * Platform.runLater.
 *
 * Dva pravila koja vlcj postavlja, a ovde su razlog za {@link #radnik}:
 * <ul>
 *   <li>iz callback-a se ne sme zvati nazad u media player - blokira se nativna
 *       nit, pa svaka reakcija na dogadjaj ide kroz izvrsioca;</li>
 *   <li>{@code play()} je asinhron, dakle jacinu tona ima smisla postaviti tek
 *       kad player javi da svira.</li>
 * </ul>
 *
 * Auto-reconnect: dokle god korisnik nije stao, pucanje strima znaci ponovno
 * povezivanje sa rastucim razmakom. Kviz traje sat i po, a Icecast ume da
 * prekine vezu bez razloga - voditelj ne sme da bude taj koji to primeti.
 */
public final class PlayerService {

    public enum Stanje {
        STOP, POVEZIVANJE, SVIRA, GRESKA
    }

    /** Sta se desava sada; {@code stanica} je null samo u stanju STOP. */
    public record Status(Stanje stanje, Stanica stanica, String poruka) {
    }

    /** Razmaci izmedju pokusaja povezivanja, u sekundama; posle se ponavlja poslednji. */
    private static final int[] CEKANJE = {2, 4, 8, 15, 30};

    /** libvlc bafer za mrezni strim; 3s je dovoljno da kratak prekid ne pukne. */
    private static final String MREZNI_BAFER = ":network-caching=3000";

    private final AudioPlayerComponent komponenta;
    private final ScheduledExecutorService radnik
            = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kvizradio-player");
                t.setDaemon(true);
                return t;
            });

    private final Consumer<Status> slusalac;
    private final Consumer<String> log;

    /** Stanica koju korisnik hoce da cuje; null znaci "stao je" - i gasi reconnect. */
    private volatile Stanica zeljena;
    private volatile int jacina = 70;
    private volatile Stanje stanje = Stanje.STOP;
    private int pokusaj;
    private ScheduledFuture<?> zakazanoPovezivanje;
    private ScheduledFuture<?> fade;

    public PlayerService(Consumer<Status> slusalac, Consumer<String> log) {
        this.slusalac = slusalac == null ? s -> {} : slusalac;
        this.log = log == null ? s -> {} : log;
        this.komponenta = new AudioPlayerComponent();
        this.komponenta.mediaPlayer().events().addMediaPlayerEventListener(new Dogadjaji());
    }

    // --------------------------------------------------------------- radnje

    /** Pusta stanicu; ako nesto vec svira, prekida se bez fejda. */
    public void pusti(Stanica stanica) {
        zeljena = stanica;
        pokusaj = 0;
        otkaziFade();
        otkaziPovezivanje();
        javi(Stanje.POVEZIVANJE, "povezujem se...");
        radnik.execute(() -> {
            komponenta.mediaPlayer().controls().stop();
            komponenta.mediaPlayer().media().play(stanica.url(), MREZNI_BAFER);
        });
    }

    /**
     * Momentalno cutanje - dugme kome voditelj mora da veruje. Prvo se gasi
     * zelja (da reconnect ne vrati muziku), pa tek onda player.
     */
    public void stop() {
        zeljena = null;
        otkaziFade();
        otkaziPovezivanje();
        javi(Stanje.STOP, "");
        radnik.execute(() -> komponenta.mediaPlayer().controls().stop());
    }

    /**
     * Postepeno utisavanje pa stop. Jacina se na kraju vraca na pocetnu, jer je
     * fade radnja nad ovim pustanjem, a ne nova podesenost aparata.
     */
    public void fadeOut(int trajanjeMs) {
        if (zeljena == null) {
            return;
        }
        otkaziFade();
        final int pocetna = jacina;
        final int korakMs = 50;
        final int koraka = Math.max(1, trajanjeMs / korakMs);
        final int[] korak = {0};

        fade = radnik.scheduleAtFixedRate(() -> {
            korak[0]++;
            int nova = Math.round(pocetna * (1f - (float) korak[0] / koraka));
            postaviNaPlayer(Math.max(0, nova));
            if (korak[0] >= koraka) {
                zeljena = null;
                otkaziPovezivanje();
                komponenta.mediaPlayer().controls().stop();
                jacina = pocetna;
                postaviNaPlayer(pocetna);
                javi(Stanje.STOP, "");
                otkaziFade();
            }
        }, korakMs, korakMs, TimeUnit.MILLISECONDS);
    }

    /** Jacina 0-100; pamti se i primenjuje ponovo kad sledece pustanje krene. */
    public void jacina(int procenat) {
        jacina = Math.max(0, Math.min(100, procenat));
        postaviNaPlayer(jacina);
    }

    public int jacina() {
        return jacina;
    }

    public Stanje stanje() {
        return stanje;
    }

    public Stanica stanica() {
        return zeljena;
    }

    /** Zove se pri gasenju aplikacije - bez ovoga nativni resursi ostaju. */
    public void oslobodi() {
        zeljena = null;
        otkaziFade();
        otkaziPovezivanje();
        radnik.execute(() -> {
            komponenta.mediaPlayer().controls().stop();
            komponenta.release();
        });
        radnik.shutdown();
        try {
            radnik.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------- interno

    private void postaviNaPlayer(int procenat) {
        radnik.execute(() -> komponenta.mediaPlayer().audio().setVolume(procenat));
    }

    private synchronized void otkaziFade() {
        if (fade != null) {
            fade.cancel(false);
            fade = null;
        }
    }

    private synchronized void otkaziPovezivanje() {
        if (zakazanoPovezivanje != null) {
            zakazanoPovezivanje.cancel(false);
            zakazanoPovezivanje = null;
        }
    }

    /** Novi pokusaj, osim ako je korisnik u medjuvremenu stao. */
    private synchronized void zakaziPonovo(String razlog) {
        Stanica stanica = zeljena;
        if (stanica == null) {
            return;
        }
        int sekundi = CEKANJE[Math.min(pokusaj, CEKANJE.length - 1)];
        pokusaj++;
        log.accept("Strim je pukao (" + razlog + "), pokusaj " + pokusaj + " za " + sekundi + "s");
        javi(Stanje.GRESKA, "veza pukla, ponovo za " + sekundi + "s");

        otkaziPovezivanje();
        zakazanoPovezivanje = radnik.schedule(() -> {
            if (zeljena != stanica) {
                return;
            }
            javi(Stanje.POVEZIVANJE, "povezujem se ponovo...");
            komponenta.mediaPlayer().controls().stop();
            komponenta.mediaPlayer().media().play(stanica.url(), MREZNI_BAFER);
        }, sekundi, TimeUnit.SECONDS);
    }

    private void javi(Stanje novo, String poruka) {
        stanje = novo;
        slusalac.accept(new Status(novo, zeljena, poruka));
    }

    private final class Dogadjaji extends MediaPlayerEventAdapter {

        @Override
        public void playing(MediaPlayer mediaPlayer) {
            pokusaj = 0;
            postaviNaPlayer(jacina);
            javi(Stanje.SVIRA, "");
        }

        @Override
        public void buffering(MediaPlayer mediaPlayer, float procenat) {
            // svaki bafer ispod 100% je jos uvek povezivanje, ne sviranje
            if (procenat < 100f && stanje != Stanje.SVIRA) {
                javi(Stanje.POVEZIVANJE, "bafer " + Math.round(procenat) + "%");
            }
        }

        @Override
        public void error(MediaPlayer mediaPlayer) {
            radnik.execute(() -> zakaziPonovo("greska playera"));
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            // strim se ne zavrsava sam - ako jeste, server je prekinuo vezu
            radnik.execute(() -> zakaziPonovo("strim zavrsen"));
        }

        @Override
        public void stopped(MediaPlayer mediaPlayer) {
            // stop na nas zahtev vec je obrisao zeljenu stanicu, pa ovo hvata
            // samo prekid koji je dosao spolja
            radnik.execute(() -> {
                if (zeljena != null && stanje != Stanje.POVEZIVANJE) {
                    zakaziPonovo("player zaustavljen");
                }
            });
        }
    }
}
