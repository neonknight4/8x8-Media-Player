module its.kvizradio {
    requires javafx.controls;
    requires java.net.http;
    requires uk.co.caprica.vlcj;

    exports its.kvizradio;
    // JavaFX pravi KvizRadioApp refleksijom, pa mu paket mora biti otvoren
    opens its.kvizradio to javafx.graphics;
}
