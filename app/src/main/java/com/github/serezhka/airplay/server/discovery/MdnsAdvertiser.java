package com.github.serezhka.airplay.server.discovery;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advertises the airplay/raop services over mDNS.
 * <p>
 * Android port. Each service is first registered through Android's system responder
 * (NsdManager), one request at a time. Some Android TV builds never answer that request for
 * {@code _airplay._tcp} (no success, no failure), which leaves iPhones unable to see the TV for
 * Screen Mirroring. So a request that times out or keeps failing falls back to our own responder,
 * jmDNS (what upstream java-airplay uses), bound to the TV's IPv4 address.
 */
@Slf4j
public class MdnsAdvertiser {

    /** Reports advertising progress; called on the main thread. */
    public interface Listener {
        /**
         * @param registered services currently live, e.g. {@code [_airplay._tcp, _raop._tcp]}
         * @param problem    human-readable description of what's still pending/failing, or null
         */
        void onAdvertisingChanged(List<String> registered, String problem);
    }

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp";
    private static final String AIRTUNES_SERVICE_TYPE = "_raop._tcp";
    /** How long to wait for NsdManager to answer before falling back to jmDNS. */
    private static final long NSD_TIMEOUT_MS = 6_000;
    /** Explicit NsdManager failures get one quick retry before falling back. */
    private static final int NSD_MAX_ATTEMPTS = 2;
    private static final long[] JMDNS_RETRY_MS = {5_000, 10_000, 30_000};

    private enum State { PENDING, NSD_IN_FLIGHT, LIVE_NSD, JMDNS_IN_FLIGHT, LIVE_JMDNS }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jmdnsExecutor = Executors.newSingleThreadExecutor();
    private final Context context;
    private final NsdManager nsdManager;
    private final String serverName;
    private final String deviceId;
    private final Listener listener;

    private final List<Registration> registrations = new ArrayList<>();
    private boolean stopped;
    private JmDNS jmdns; // touched only on jmdnsExecutor
    private WifiManager.MulticastLock multicastLock;

    /**
     * @param deviceId stable pseudo MAC address, e.g. {@code 02:1A:2B:3C:4D:5E}
     */
    public MdnsAdvertiser(Context context, String serverName, String deviceId, Listener listener) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.serverName = serverName;
        this.deviceId = deviceId;
        this.listener = listener;
    }

    public void start(int airTunesPort) {
        main.post(() -> {
            String airTunesServerName = deviceId.replace(":", "") + "@" + serverName;
            registrations.add(new Registration(serverName, AIRPLAY_SERVICE_TYPE, airTunesPort, airPlayMDNSProps(deviceId)));
            registrations.add(new Registration(airTunesServerName, AIRTUNES_SERVICE_TYPE, airTunesPort, airTunesMDNSProps()));
            report();
            advance();
        });
    }

    public void stop() {
        main.post(() -> {
            stopped = true;
            main.removeCallbacksAndMessages(null);
            for (Registration r : registrations) {
                r.unregisterNsd();
            }
            registrations.clear();
            jmdnsExecutor.execute(() -> {
                if (jmdns != null) {
                    try {
                        jmdns.unregisterAllServices();
                        jmdns.close();
                    } catch (Exception e) {
                        log.warn("jmDNS close failed", e);
                    }
                    jmdns = null;
                }
            });
            jmdnsExecutor.shutdown();
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        });
    }

    /** Starts the next NsdManager request; only one is ever in flight. */
    private void advance() {
        if (stopped) {
            return;
        }
        for (Registration r : registrations) {
            if (r.state == State.NSD_IN_FLIGHT) {
                return;
            }
        }
        for (Registration r : registrations) {
            if (r.state == State.PENDING && !r.waiting) {
                r.registerNsd();
                return;
            }
        }
    }

    private void report() {
        List<String> live = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (Registration r : registrations) {
            if (r.state == State.LIVE_NSD || r.state == State.LIVE_JMDNS) {
                live.add(r.type);
            } else if (r.problem != null) {
                if (pending.length() > 0) {
                    pending.append('\n');
                }
                pending.append(r.type).append(": ").append(r.problem);
            }
        }
        if (listener != null) {
            listener.onAdvertisingChanged(live, pending.length() > 0 ? pending.toString() : null);
        }
    }

    private final class Registration {
        final String name;
        final String type;
        final int port;
        final Map<String, String> txt;

        State state = State.PENDING;
        boolean waiting; // a retry is scheduled
        int nsdAttempts;
        int jmdnsAttempts;
        String problem;
        Attempt current;

        Registration(String name, String type, int port, Map<String, String> txt) {
            this.name = name;
            this.type = type;
            this.port = port;
            this.txt = txt;
        }

        void registerNsd() {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(type);
            info.setPort(port);
            for (Map.Entry<String, String> e : txt.entrySet()) {
                info.setAttribute(e.getKey(), e.getValue());
            }
            state = State.NSD_IN_FLIGHT;
            nsdAttempts++;
            // A listener object can only be registered once, so each attempt uses a fresh one.
            Attempt attempt = new Attempt(this);
            current = attempt;
            main.postDelayed(attempt.timeout, NSD_TIMEOUT_MS);
            try {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, attempt);
            } catch (RuntimeException e) {
                main.removeCallbacks(attempt.timeout);
                onNsdFailed(attempt, "registerService threw " + e.getClass().getSimpleName(), false);
            }
        }

        void unregisterNsd() {
            Attempt attempt = current;
            current = null;
            if (attempt == null) {
                return;
            }
            main.removeCallbacks(attempt.timeout);
            try {
                nsdManager.unregisterService(attempt);
            } catch (RuntimeException ignored) {
                // never registered / already gone
            }
        }

        void onNsdRegistered(Attempt attempt, String actualName) {
            if (attempt != current || state != State.NSD_IN_FLIGHT) {
                return; // a late answer after we gave up on it
            }
            main.removeCallbacks(attempt.timeout);
            state = State.LIVE_NSD;
            problem = null;
            log.info("{} registered via NsdManager as '{}' on port {}", type, actualName, port);
            report();
            advance();
        }

        void onNsdFailed(Attempt attempt, String reason, boolean timedOut) {
            if (attempt != current || state != State.NSD_IN_FLIGHT) {
                return;
            }
            log.error("{} NsdManager registration failed: {}", type, reason);
            unregisterNsd(); // cancel a hung request so it can't surface later as a duplicate
            state = State.PENDING;
            if (!timedOut && nsdAttempts < NSD_MAX_ATTEMPTS) {
                problem = reason + ", retrying";
                waiting = true;
                main.postDelayed(() -> {
                    waiting = false;
                    advance();
                }, 1_000);
            } else {
                problem = reason + ", using fallback";
                registerJmdns();
            }
            report();
            advance(); // let the other service go ahead meanwhile
        }

        void registerJmdns() {
            state = State.JMDNS_IN_FLIGHT;
            jmdnsAttempts++;
            jmdnsExecutor.execute(() -> {
                String error = null;
                try {
                    JmDNS j = jmdns();
                    j.registerService(ServiceInfo.create(type + ".local.", name, port, 0, 0, txt));
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    log.error("{} jmDNS registration failed", type, e);
                }
                String failure = error;
                main.post(() -> {
                    if (stopped) {
                        return;
                    }
                    if (failure == null) {
                        state = State.LIVE_JMDNS;
                        problem = null;
                        log.info("{} registered via jmDNS as '{}' on port {}", type, name, port);
                    } else {
                        state = State.PENDING;
                        waiting = true;
                        problem = "fallback failed (" + failure + "), retrying";
                        long delay = JMDNS_RETRY_MS[Math.min(jmdnsAttempts - 1, JMDNS_RETRY_MS.length - 1)];
                        main.postDelayed(() -> {
                            waiting = false;
                            registerJmdns();
                        }, delay);
                    }
                    report();
                });
            });
        }
    }

    /** One NsdManager registration attempt; callbacks may arrive on a binder thread. */
    private final class Attempt implements NsdManager.RegistrationListener {
        final Registration owner;
        final Runnable timeout;

        Attempt(Registration owner) {
            this.owner = owner;
            this.timeout = () -> owner.onNsdFailed(this, "no response from Android's discovery service", true);
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            main.post(() -> {
                if (!stopped) {
                    owner.onNsdRegistered(this, serviceInfo.getServiceName());
                }
            });
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            main.post(() -> {
                if (!stopped) {
                    owner.onNsdFailed(this, "error " + errorCode + " (" + describe(errorCode) + ")", false);
                }
            });
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            log.info("{} NsdManager registration removed", owner.type);
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            log.warn("{} NsdManager unregistration failed: {}", owner.type, errorCode);
        }
    }

    /** Lazily creates our own responder on the TV's IPv4 address (jmdnsExecutor only). */
    private JmDNS jmdns() throws Exception {
        if (jmdns == null) {
            InetAddress address = localIpv4();
            if (address == null) {
                throw new IllegalStateException("no IPv4 address");
            }
            main.post(() -> {
                if (multicastLock == null) {
                    WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    multicastLock = wifi.createMulticastLock("AirPlayTV:mdns");
                    multicastLock.setReferenceCounted(false);
                }
                if (!stopped) {
                    multicastLock.acquire(); // Wi-Fi drops multicast queries without it
                }
            });
            String host = "AirPlayTV-" + deviceId.replace(":", "");
            jmdns = JmDNS.create(address, host);
            log.info("jmDNS responder started on {} as {}.local", address.getHostAddress(), host);
        }
        return jmdns;
    }

    private InetAddress localIpv4() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
        if (props == null) {
            return null;
        }
        for (LinkAddress address : props.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                return address.getAddress();
            }
        }
        return null;
    }

    private static String describe(int errorCode) {
        switch (errorCode) {
            case NsdManager.FAILURE_INTERNAL_ERROR:
                return "internal error";
            case NsdManager.FAILURE_ALREADY_ACTIVE:
                return "already active";
            case NsdManager.FAILURE_MAX_LIMIT:
                return "too many requests";
            default:
                return "unknown";
        }
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        Map<String, String> airPlayMDNSProps = new LinkedHashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        // Bit 27 off (legacy pairing); LO bits 0/4/8 restored — see AdvertisedReceiver.
        airPlayMDNSProps.put("features", AdvertisedReceiver.FEATURES_TXT);
        airPlayMDNSProps.put("srcvers", AdvertisedReceiver.SOURCE_VERSION);
        airPlayMDNSProps.put("flags", AdvertisedReceiver.MDNS_FLAGS);
        airPlayMDNSProps.put("vv", Integer.toString(AdvertisedReceiver.VV));
        airPlayMDNSProps.put("model", AdvertisedReceiver.MODEL);
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        Map<String, String> airTunesMDNSProps = new LinkedHashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        airTunesMDNSProps.put("ft", AdvertisedReceiver.FEATURES_TXT);
        airTunesMDNSProps.put("am", AdvertisedReceiver.MODEL);
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", AdvertisedReceiver.MDNS_FLAGS);
        airTunesMDNSProps.put("vs", AdvertisedReceiver.SOURCE_VERSION);
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return airTunesMDNSProps;
    }
}
