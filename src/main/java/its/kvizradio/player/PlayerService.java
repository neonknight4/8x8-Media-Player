package its.kvizradio.player;

import its.kvizradio.radio.Pesma;
import its.kvizradio.radio.Stanica;

import uk.co.caprica.vlcj.media.Meta;
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

    /**
     * Sta se desava sada; {@code stanica} je null samo u stanju STOP, a
     * {@code pesma} kad strim ne salje naziv (vecina ih ne salje).
     */
    public record Status(Stanje stanje, Stanica stanica, String poruka, Pesma pesma) {
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
    private volatile boolean prigusen;
    private volatile Stanje stanje = Stanje.STOP;
    private int pokusaj;
    private ScheduledFuture<?> zakazanoPovezivanje;
    private ScheduledFuture<?> fade;
    private ScheduledFuture<?> primenaJacine;
    private ScheduledFuture<?> citanjeMeta;
    private volatile Pesma pesma;
    /** Posle oslobodi() radnik vise ne prima posao; dogadjaji jos umeju da stignu. */
    private volatile boolean ugasen;

    public PlayerService(Consumer<Status> slusalac, Consumer<String> log) {
        this.slusalac = slusalac == null ? s -> {} : slusalac;
        this.log = log == null ? s -> {} : log;
        pripremiLibVlc(this.log);
        this.komponenta = new AudioPlayerComponent();
        this.komponenta.mediaPlayer().events().addMediaPlayerEventListener(new Dogadjaji());
    }

    /**
     * Instalacija nosi svoj VLC u podfolderu "vlc" (libvlc.dll, libvlccore.dll,
     * plugins\). vlcj ga nadje preko jna.library.path - jedan od njegovih
     * provajdera cita bas tu osobinu.
     *
     * Ako tog foldera nema (razvoj, Linux), ostaje vlcj-jeva potraga po sistemu.
     * Isto pravilo kao u HUB-u za yt-dlp i ffmpeg: sto instalacija donese, to se
     * i koristi, da verzija ne zavisi od toga sta je na masini.
     */
    private static void pripremiLibVlc(Consumer<String> log) {
        java.nio.file.Path vlc = its.kvizradio.Alati.nadjiFolder("vlc");
        if (vlc != null) {
            System.setProperty("jna.library.path", vlc.toString());
            log.accept("libvlc uz aplikaciju: " + vlc);
        }
    }

    // --------------------------------------------------------------- radnje

    /** Pusta stanicu; ako nesto vec svira, prekida se bez fejda. */
    public void pusti(Stanica stanica) {
        zeljena = stanica;
        pesma = null;
        pokusaj = 0;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziPovezivanje();
        javi(Stanje.POVEZIVANJE, "povezujem se...");
        izvrsi(() -> {
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
        pesma = null;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziCitanjeMeta();
        otkaziPovezivanje();
        javi(Stanje.STOP, "");
        izvrsi(() -> komponenta.mediaPlayer().controls().stop());
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
        otkaziPrimenuJacine();
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
        // pomeranje jacine je i znak da se vise ne trazi tisina
        prigusen = false;
        postaviNaPlayer(jacina);
    }

    /**
     * Prigusenje: jacina na nulu, a zapamcena vrednost ostaje - da se jednim
     * klikom vrati tacno onako kako je bilo.
     *
     * Ide preko jacine, ne preko libvlc mute-a: isti put koji vec proverljivo
     * radi, i slajder ostaje tamo gde ga je voditelj ostavio.
     */
    public void prigusi(boolean da) {
        prigusen = da;
        postaviNaPlayer(da ? 0 : jacina);
    }

    public boolean prigusen() {
        return prigusen;
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

    public Pesma pesma() {
        return pesma;
    }

    /** Naziv koji je stigao spolja (prepoznavanje) - da ga bar prikaze isto. */
    public void postaviPesmu(Pesma nova) {
        pesma = nova;
        javi(stanje, "");
    }

    /** Zove se pri gasenju aplikacije - bez ovoga nativni resursi ostaju. */
    public void oslobodi() {
        zeljena = null;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziCitanjeMeta();
        otkaziPovezivanje();
        izvrsi(() -> {
            komponenta.mediaPlayer().controls().stop();
            komponenta.release();
        });
        ugasen = true;
        radnik.shutdown();
        try {
            radnik.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------- interno

    /**
     * Posao za radnika, osim kad se aplikacija gasi.
     *
     * Gasenje ide preko controls().stop(), a taj poziv sam izazove "stopped"
     * dogadjaj - koji bi opet hteo na radnika koji je vec ugasen. Bez ove
     * provere na izlasku iskoci RejectedExecutionException iz JNA callback-a.
     */
    private void izvrsi(Runnable posao) {
        if (!ugasen) {
            radnik.execute(posao);
        }
    }

    private void postaviNaPlayer(int procenat) {
        izvrsi(() -> komponenta.mediaPlayer().audio().setVolume(procenat));
    }

    /**
     * Jacina posle pocetka sviranja, upisana onoliko puta koliko treba da se
     * primi.
     *
     * libvlc odbacuje {@code setVolume} dok audio izlaz jos nije napravljen -
     * merenje: odmah posle {@code play()} procitana jacina je 2 (vrednost iz
     * vlcrc-a), a ne ono sto smo upisali, pa se ne cuje nista. Cak i upis iz
     * {@code playing} dogadjaja ume da bude prerani. Zato se pise u razmacima i
     * proverava citanjem, dok se ne poklopi.
     *
     * Ne sme da blokira radnika: na njemu visi i stop, a dugme za tisinu je
     * jedino koje mora da odgovori odmah.
     */
    private synchronized void primeniJacinu() {
        otkaziPrimenuJacine();
        final int[] pokusaja = {0};
        primenaJacine = radnik.scheduleAtFixedRate(() -> {
            int cilj = prigusen ? 0 : jacina;
            komponenta.mediaPlayer().audio().setVolume(cilj);
            if (komponenta.mediaPlayer().audio().volume() == cilj || ++pokusaja[0] >= 20) {
                otkaziPrimenuJacine();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    private synchronized void otkaziPrimenuJacine() {
        if (primenaJacine != null) {
            primenaJacine.cancel(false);
            primenaJacine = null;
        }
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
        slusalac.accept(new Status(novo, zeljena, poruka, pesma));
    }

    /**
     * Naziv pesme iz ICY metapodataka strima.
     *
     * vlcj nema dogadjaj za promenu metapodataka na plejeru, pa se cita svakih
     * par sekundi - poziv je lokalan i besplatan.
     *
     * Dosta stanica u NOW_PLAYING drzi svoje ime umesto pesme; zato se odbacuje
     * sve sto je isto kao ime stanice ili kao naslov strima.
     */
    private synchronized void citajMeta() {
        otkaziCitanjeMeta();
        citanjeMeta = radnik.scheduleAtFixedRate(() -> {
            Stanica stanica = zeljena;
            if (stanica == null) {
                return;
            }
            var meta = komponenta.mediaPlayer().media().meta();
            if (meta == null) {
                return;
            }
            String sada = meta.get(Meta.NOW_PLAYING);
            String naslovStrima = meta.get(Meta.TITLE);
            if (sada == null || sada.isBlank()
                    || sada.equalsIgnoreCase(stanica.ime())
                    || sada.equalsIgnoreCase(naslovStrima)) {
                return;
            }
            Pesma nova = Pesma.izNaziva(sada, Pesma.IZ_STRIMA);
            if (!nova.equals(pesma)) {
                pesma = nova;
                javi(stanje, "");
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    private synchronized void otkaziCitanjeMeta() {
        if (citanjeMeta != null) {
            citanjeMeta.cancel(false);
            citanjeMeta = null;
        }
    }

    private final class Dogadjaji extends MediaPlayerEventAdapter {

        @Override
        public void playing(MediaPlayer mediaPlayer) {
            pokusaj = 0;
            primeniJacinu();
            citajMeta();
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
            izvrsi(() -> zakaziPonovo("greska playera"));
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            // strim se ne zavrsava sam - ako jeste, server je prekinuo vezu
            izvrsi(() -> zakaziPonovo("strim zavrsen"));
        }

        @Override
        public void stopped(MediaPlayer mediaPlayer) {
            // stop na nas zahtev vec je obrisao zeljenu stanicu, pa ovo hvata
            // samo prekid koji je dosao spolja
            izvrsi(() -> {
                if (zeljena != null && stanje != Stanje.POVEZIVANJE) {
                    zakaziPonovo("player zaustavljen");
                }
            });
        }
    }
}
