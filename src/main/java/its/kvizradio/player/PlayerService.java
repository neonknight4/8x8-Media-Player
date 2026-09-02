package its.kvizradio.player;

import its.kvizradio.radio.IcyMeta;
import its.kvizradio.radio.Pesma;
import its.kvizradio.radio.Stanica;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.media.Meta;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.nio.ByteBuffer;

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
        STOP, POVEZIVANJE, SVIRA, PAUZA, GRESKA
    }

    /** Gde se stalo u numeri; za radio nema smisla, pa se ni ne javlja. */
    public record Napredak(long protekloMs, long ukupnoMs) {
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

    /**
     * HLS strim (RTS-ovi m3u8) dolazi u .ts komadima od desetak sekundi, a svaki
     * komad pocinje continuity counter-om od nule. VLC to cita kao prekid u
     * prenosu, resetuje sat i ubaci tisinu - mereno na Radiju Beograd 202: devet
     * prekida od ~130ms u sto sekundi, dakle na svakih pet do deset sekundi. Bez
     * provere brojaca nema ni jednog (mereno istim merenjem).
     *
     * Obicnom Icecast strimu ne menja nista - tamo TS-a nema.
     */
    private static final String BEZ_CC_PROVERE = ":no-ts-cc-check";

    /**
     * Spektar: VLC-ov "visual" modul crta spektar kao sliku, a mi iz te slike
     * citamo samo visine po opsezima - sama slika se nigde ne prikazuje, trake
     * crta UI u bojama aplikacije.
     *
     * Opcije moraju na fabriku (instancu libvlc-a), ne na medij: kao opcije
     * medija se ne primene i ne stigne nijedan kadar.
     */
    /** Koliko VLC pojacava spektar; podrazumevano je prejako, sve trake stoje uz vrh. */
    private static final int POJACANJE = Integer.getInteger("kvizradio.pojacanje", 1);
    private static final int EFEKT_SIRINA = 160;
    private static final int EFEKT_VISINA = 64;
    /** VLC bez --spect-80-bands crta dvadeset opsega; toliko i citamo. */
    public static final int TRAKA = 20;

    private final MediaPlayerFactory fabrika;
    private final EmbeddedMediaPlayer plejer;
    /** JNA drzi samo slabu referencu na povratne pozive - bez polja ih pokupi GC. */
    private final BufferFormatCallback formatKadra;
    private final RenderCallbackAdapter citacKadra;
    private volatile float[] nivoi = new float[TRAKA];
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
    private ScheduledFuture<?> citanjeIcy;
    private volatile Pesma pesma;

    private final IcyMeta icy = new IcyMeta();

    /** Lokalni fajl se ponasa drugacije od strima: ima kraj, pauzu i premotavanje. */
    private volatile boolean lokalni;
    private volatile Consumer<Napredak> naNapredak = n -> { };
    private volatile Runnable naKrajNumere = () -> { };
    private ScheduledFuture<?> pracenjeNapretka;

    /**
     * Citanje naziva pesme sa strima ide svojom niti, ne radnikovom: zahtev ume
     * da visi do petnaest sekundi, a na radniku ceka i stop - dugme za tisinu ne
     * sme da ceka mrezu.
     */
    private final ScheduledExecutorService citac
            = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kvizradio-meta");
                t.setDaemon(true);
                return t;
            });
    /** Posle oslobodi() radnik vise ne prima posao; dogadjaji jos umeju da stignu. */
    private volatile boolean ugasen;

    public PlayerService(Consumer<Status> slusalac, Consumer<String> log) {
        this.slusalac = slusalac == null ? s -> {} : slusalac;
        this.log = log == null ? s -> {} : log;
        pripremiLibVlc(this.log);

        this.fabrika = new MediaPlayerFactory(
                "--audio-visual=visual", "--effect-list=spectrum",
                "--effect-width=" + EFEKT_SIRINA, "--effect-height=" + EFEKT_VISINA,
                "--no-spect-80-bands", "--no-spect-show-peaks", "--no-spect-show-base",
                "--spect-separ=1", "--spect-amp=" + POJACANJE,
                "--no-video-title-show");
        this.plejer = fabrika.mediaPlayers().newEmbeddedMediaPlayer();
        this.formatKadra = new BufferFormatCallback() {
            @Override
            public BufferFormat getBufferFormat(int sirina, int visina) {
                return new RV32BufferFormat(EFEKT_SIRINA, EFEKT_VISINA);
            }

            @Override
            public void newFormatSize(int bs, int bv, int ps, int pv) {
            }

            @Override
            public void allocatedBuffers(ByteBuffer[] baferi) {
            }
        };
        this.citacKadra = new RenderCallbackAdapter(new int[EFEKT_SIRINA * EFEKT_VISINA]) {
            @Override
            protected void onDisplay(MediaPlayer mediaPlayer, int[] piksela) {
                nivoi = izmeriNivoe(piksela);
            }
        };
        this.plejer.videoSurface().set(
                fabrika.videoSurfaces().newVideoSurface(formatKadra, citacKadra, true));
        this.plejer.events().addMediaPlayerEventListener(new Dogadjaji());
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

    /** Javlja gde se stiglo u numeri, desetak puta u minuti - dovoljno za traku. */
    public void postaviSlusaocaNapretka(Consumer<Napredak> slusalac) {
        this.naNapredak = slusalac == null ? n -> { } : slusalac;
    }

    /** Zove se kad se numera zavrsi - red sviranja odatle pusta sledecu. */
    public void postaviKrajNumere(Runnable naKraj) {
        this.naKrajNumere = naKraj == null ? () -> { } : naKraj;
    }

    /**
     * Pusta fajl sa diska.
     *
     * Za razliku od strima, kraj fajla je normalan zavrsetak a ne pucanje veze,
     * pa se ne ide u ponovno povezivanje nego u sledecu numeru.
     */
    public void pustiFajl(java.nio.file.Path fajl) {
        zeljena = null;
        pesma = null;
        lokalni = true;
        pokusaj = 0;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziCitanjeMeta();
        otkaziPovezivanje();
        javi(Stanje.POVEZIVANJE, "");
        izvrsi(() -> {
            plejer.controls().stop();
            plejer.media().play(fajl.toString());
        });
    }

    /** Pauza i nastavak; radio nema pauzu, tamo se koristi stop. */
    public void pauza(boolean pauziraj) {
        if (!lokalni) {
            return;
        }
        izvrsi(() -> plejer.controls().setPause(pauziraj));
        javi(pauziraj ? Stanje.PAUZA : Stanje.SVIRA, "");
    }

    public boolean lokalni() {
        return lokalni;
    }

    /** Premotavanje na deo numere, 0..1. */
    public void premotaj(double udeo) {
        if (!lokalni) {
            return;
        }
        izvrsi(() -> plejer.controls().setPosition((float) Math.max(0, Math.min(1, udeo))));
    }

    /** Pusta stanicu; ako nesto vec svira, prekida se bez fejda. */
    public void pusti(Stanica stanica) {
        zeljena = stanica;
        pesma = null;
        lokalni = false;
        pokusaj = 0;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziPovezivanje();
        javi(Stanje.POVEZIVANJE, "povezujem se...");
        izvrsi(() -> {
            plejer.controls().stop();
            plejer.media().play(stanica.url(), MREZNI_BAFER, BEZ_CC_PROVERE);
        });
    }

    /**
     * Momentalno cutanje - dugme kome voditelj mora da veruje. Prvo se gasi
     * zelja (da reconnect ne vrati muziku), pa tek onda player.
     */
    public void stop() {
        zeljena = null;
        pesma = null;
        lokalni = false;
        otkaziFade();
        otkaziPrimenuJacine();
        otkaziCitanjeMeta();
        otkaziPovezivanje();
        javi(Stanje.STOP, "");
        izvrsi(() -> plejer.controls().stop());
    }

    /**
     * Postepeno utisavanje pa stop. Jacina se na kraju vraca na pocetnu, jer je
     * fade radnja nad ovim pustanjem, a ne nova podesenost aparata.
     *
     * Lokalni fajl nema {@code zeljena} - tamo je znak da nesto ide to sto je
     * {@code lokalni} podignut.
     */
    public void fadeOut(int trajanjeMs) {
        if (zeljena == null && !lokalni) {
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
                lokalni = false;
                otkaziPovezivanje();
                plejer.controls().stop();
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

    /**
     * Visine opsega, 0..1, sveze koliko i poslednji kadar spektra. Kad nista ne
     * svira, sve su nule - pa se trake same spuste.
     */
    public float[] nivoi() {
        return stanje == Stanje.SVIRA ? nivoi : new float[TRAKA];
    }

    /**
     * Iz slike spektra u visine: za svaki opseg se trazi najvisi obojen piksel.
     * Slika je RV32, pa je "obojen" sve sto nije crno.
     */
    private static float[] izmeriNivoe(int[] piksela) {
        float[] izmereno = new float[TRAKA];
        int poTraci = Math.max(1, EFEKT_SIRINA / TRAKA);
        for (int traka = 0; traka < TRAKA; traka++) {
            int odX = traka * poTraci;
            int doX = Math.min(EFEKT_SIRINA, odX + poTraci);
            int najvisi = EFEKT_VISINA;
            for (int y = 0; y < EFEKT_VISINA; y++) {
                int red = y * EFEKT_SIRINA;
                for (int x = odX; x < doX; x++) {
                    if ((piksela[red + x] & 0xFFFFFF) != 0) {
                        najvisi = y;
                        y = EFEKT_VISINA;
                        break;
                    }
                }
            }
            izmereno[traka] = 1f - (float) najvisi / EFEKT_VISINA;
        }
        return izmereno;
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
            plejer.controls().stop();
            plejer.release();
            fabrika.release();
        });
        ugasen = true;
        citac.shutdownNow();
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
        izvrsi(() -> plejer.audio().setVolume(procenat));
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
            plejer.audio().setVolume(cilj);
            if (plejer.audio().volume() == cilj || ++pokusaja[0] >= 20) {
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
            plejer.controls().stop();
            plejer.media().play(stanica.url(), MREZNI_BAFER, BEZ_CC_PROVERE);
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
            var meta = plejer.media().meta();
            if (meta == null) {
                return;
            }
            String sada = meta.get(Meta.NOW_PLAYING);
            String naslovStrima = meta.get(Meta.TITLE);
            if (!Pesma.upotrebljivNaziv(sada)
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
        if (citanjeIcy != null) {
            citanjeIcy.cancel(true);
            citanjeIcy = null;
        }
        if (pracenjeNapretka != null) {
            pracenjeNapretka.cancel(false);
            pracenjeNapretka = null;
        }
    }

    /** Pozicija u numeri; vlcj nema dogadjaj za to koji bi bio dovoljno gust. */
    private synchronized void pratiNapredak() {
        pracenjeNapretka = radnik.scheduleAtFixedRate(() -> {
            long ukupno = plejer.status().length();
            long proteklo = plejer.status().time();
            if (ukupno > 0 && proteklo >= 0) {
                naNapredak.accept(new Napredak(proteklo, ukupno));
            }
        }, 200, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * Naziv pesme procitan direktno sa strima. Radi za stanice kod kojih vlcj
     * vraca prazno, pa ide uz njega, a ne umesto njega.
     */
    private synchronized void citajIcy() {
        if (ugasen) {
            return;
        }
        citanjeIcy = citac.scheduleWithFixedDelay(() -> {
            Stanica stanica = zeljena;
            if (stanica == null) {
                return;
            }
            Pesma nova = icy.procitaj(stanica);
            if (nova != null && !nova.prazna() && !nova.equals(pesma)
                    && !nova.naslov().equalsIgnoreCase(stanica.ime())) {
                pesma = nova;
                javi(stanje, "");
            }
        }, 0, 15, TimeUnit.SECONDS);
    }

    private final class Dogadjaji extends MediaPlayerEventAdapter {

        @Override
        public void playing(MediaPlayer mediaPlayer) {
            pokusaj = 0;
            primeniJacinu();
            if (lokalni) {
                pratiNapredak();
            } else {
                citajMeta();
                citajIcy();
            }
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
            if (lokalni) {
                // fajl se normalno zavrsio - sledeca numera, ne ponovno povezivanje
                izvrsi(naKrajNumere);
                return;
            }
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
