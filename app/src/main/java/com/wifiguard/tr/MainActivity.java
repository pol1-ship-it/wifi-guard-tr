package com.wifiguard.tr;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.net.wifi.WifiManager;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.net.InetAddress;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.view.Gravity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Set;
import java.util.HashSet;

public class MainActivity extends Activity {

    private static final String API_URL =
        "https://api.github.com/repos/pol1-ship-it/wifi-guard-tr/releases/latest";

    private WebView webView;
    private TextView statusText;
    private Button actionButton;
    private File pendingApk;
    private long pendingVersionCode = -1;

    private boolean installRequested = false;
    private boolean installerLaunched = false;
    private boolean waitingForInstallPermission = false;
    private boolean appOpened = false;

    private final Handler mainHandler =
        new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isWifiConnected()) {
            showUpdateScreen("Wi-Fi bağlantısı algılandı.\nGüncellemeler kontrol ediliyor...");
            new Thread(this::checkForUpdate).start();
        } else {
            openMainApp();
        }
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);

            Network network = cm.getActiveNetwork();

            if (network == null) return false;

            NetworkCapabilities caps =
                cm.getNetworkCapabilities(network);

            return caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

        } catch (Exception e) {
            return false;
        }
    }

    private void checkForUpdate() {
        try {
            JSONObject release = getJson(API_URL);
            JSONArray assets = release.getJSONArray("assets");

            String metadataUrl = null;
            String apkUrl = null;

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");

                if (name.equals("version.json")) {
                    metadataUrl = asset.getString("browser_download_url");
                }
            }

            if (metadataUrl == null) {
                throw new Exception("Sürüm bilgisi bulunamadı.");
            }

            JSONObject metadata = getJson(metadataUrl);

            String apkName = metadata.getString("apk");
            String expectedHash = metadata.getString("sha256");

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);

                if (asset.getString("name").equals(apkName)) {
                    apkUrl = asset.getString("browser_download_url");
                    break;
                }
            }

            if (apkUrl == null) {
                throw new Exception("Güncelleme APK dosyası bulunamadı.");
            }

            long latestVersion = metadata.getLong("versionCode");

            PackageInfo installed =
                getPackageManager().getPackageInfo(getPackageName(), 0);

            long currentVersion;

            if (Build.VERSION.SDK_INT >= 28) {
                currentVersion = installed.getLongVersionCode();
            } else {
                currentVersion = installed.versionCode;
            }

            if (latestVersion <= currentVersion) {
                mainHandler.post(this::openMainApp);
                return;
            }

            updateStatus(
                "Yeni sürüm bulundu: " +
                metadata.getString("versionName") +
                "\nGüncelleme indiriliyor...");

            File directory = new File(getFilesDir(), "updates");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new Exception("Güncelleme klasörü oluşturulamadı.");
            }

            File apk = new File(directory, "update.apk");
            downloadFile(apkUrl, apk);

            updateStatus("İndirilen dosyanın SHA-256 değeri kontrol ediliyor...");

            String actualHash = sha256(apk);

            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new SecurityException(
                    "SHA-256 doğrulaması başarısız. APK güvenli değil.");
            }

            updateStatus("Uygulama imzası kontrol ediliyor...");

            if (!sameSigningCertificate(apk)) {
                throw new SecurityException(
                    "APK imzası mevcut uygulamayla eşleşmiyor.");
            }

            pendingApk = apk;
            pendingVersionCode = latestVersion;

            mainHandler.post(() -> {
                showUpdateScreen(
                    "WiFi Guard TR için zorunlu güncelleme hazır.\n\n" +
                    "Yeni sürüm: " +
                    metadata.optString("versionName") +
                    "\n\nDevam etmek için güncellemeyi yükle.");
                actionButton.setText("GÜNCELLEMEYİ YÜKLE");
                actionButton.setOnClickListener(v -> beginInstallation());
            });

        } catch (SecurityException e) {
            mainHandler.post(() -> {
                showUpdateScreen(
                    "GÜVENLİK UYARISI\n\n" + e.getMessage() +
                    "\n\nGüvenlik doğrulaması başarısız olduğu için kurulum engellendi.");
                actionButton.setText("TEKRAR DENE");
                actionButton.setOnClickListener(v ->
                    new Thread(this::checkForUpdate).start());
            });

        } catch (Exception e) {
            mainHandler.post(() -> {
                Toast.makeText(
                    this,
                    "Güncelleme kontrol edilemedi: " + e.getMessage(),
                    Toast.LENGTH_LONG
                ).show();

                openMainApp();
            });
        }
    }

    private JSONObject getJson(String address) throws Exception {
        String response = readUrl(address);
        return new JSONObject(response);
    }

    private String readUrl(String address) throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(address).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty(
            "User-Agent", "WiFi-Guard-TR-Android");
        connection.setRequestProperty(
            "Accept", "application/vnd.github+json");

        try {
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            input.close();
            return output.toString("UTF-8");

        } finally {
            connection.disconnect();
        }
    }

    private void downloadFile(String address, File destination)
        throws Exception {

        HttpURLConnection connection =
            (HttpURLConnection) new URL(address).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty(
            "User-Agent", "WiFi-Guard-TR-Android");

        try {
            InputStream input = connection.getInputStream();
            FileOutputStream output =
                new FileOutputStream(destination);

            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();
            output.close();
            input.close();

        } finally {
            connection.disconnect();
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        InputStream input = new java.io.FileInputStream(file);

        byte[] buffer = new byte[8192];
        int count;

        while ((count = input.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
        }

        input.close();

        StringBuilder result = new StringBuilder();

        for (byte b : digest.digest()) {
            result.append(String.format("%02x", b & 0xff));
        }

        return result.toString();
    }

    private Set<String> signerDigests(PackageInfo info)
        throws Exception {

        Set<String> result = new HashSet<>();
        Signature[] signatures;

        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) {
                throw new SecurityException("APK imza bilgisi bulunamadı.");
            }

            signatures = info.signingInfo.getApkContentsSigners();

        } else {
            signatures = info.signatures;
        }

        if (signatures == null || signatures.length == 0) {
            throw new SecurityException("APK imzası boş.");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (Signature signature : signatures) {
            byte[] hash = digest.digest(signature.toByteArray());

            StringBuilder value = new StringBuilder();

            for (byte b : hash) {
                value.append(String.format("%02x", b & 0xff));
            }

            result.add(value.toString());
        }

        return result;
    }

    private boolean sameSigningCertificate(File apk)
        throws Exception {

        PackageInfo installed =
            getPackageManager().getPackageInfo(
                getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);

        PackageInfo downloaded =
            getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);

        if (downloaded == null ||
            !getPackageName().equals(downloaded.packageName)) {
            return false;
        }

        return signerDigests(installed).equals(
            signerDigests(downloaded));
    }

    private void beginInstallation() {
        if (pendingApk == null) return;

        installRequested = true;

        if (Build.VERSION.SDK_INT >= 26 &&
            !getPackageManager().canRequestPackageInstalls()) {

            showUpdateScreen(
                "Android ayarlarından WiFi Guard TR için " +
                "bu kaynaktan uygulama yükleme izni vermelisin.");

            actionButton.setText("YÜKLEME İZNİNİ AÇ");

            actionButton.setOnClickListener(v -> {
                waitingForInstallPermission = true;

                Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));

                startActivity(intent);
            });

            return;
        }

        installApk();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!installRequested || pendingApk == null) return;

        if (isPendingUpdateInstalled()) {
            finishSuccessfulInstallation();
            return;
        }

        if (waitingForInstallPermission) {
            waitingForInstallPermission = false;

            if (Build.VERSION.SDK_INT < 26 ||
                getPackageManager().canRequestPackageInstalls()) {
                installApk();
            }
        }
    }

    private void installApk() {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                pendingApk);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(
                apkUri,
                "application/vnd.android.package-archive");

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            installerLaunched = true;
            startActivityForResult(intent, 500);

        } catch (Exception e) {
            installerLaunched = false;

            showUpdateScreen(
                "Kurulum ekranı açılamadı: " + e.getMessage());
        }
    }

    private boolean isPendingUpdateInstalled() {
        if (pendingVersionCode <= 0) return false;

        try {
            PackageInfo installed =
                getPackageManager().getPackageInfo(
                    getPackageName(), 0);

            long installedVersion;

            if (Build.VERSION.SDK_INT >= 28) {
                installedVersion = installed.getLongVersionCode();
            } else {
                installedVersion = installed.versionCode;
            }

            return installedVersion >= pendingVersionCode;

        } catch (Exception e) {
            return false;
        }
    }

    private void finishSuccessfulInstallation() {
        pendingApk = null;
        pendingVersionCode = -1;
        installRequested = false;
        installerLaunched = false;

        openMainApp();
    }

    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 500 && pendingApk != null) {
            installerLaunched = false;

            if (isPendingUpdateInstalled()) {
                finishSuccessfulInstallation();
            } else {
                showUpdateScreen(
                    "Güncelleme henüz tamamlanmadı.\n" +
                    "Devam etmek için güncellemeyi yüklemelisin.");

                actionButton.setText("TEKRAR YÜKLE");
                actionButton.setOnClickListener(v -> beginInstallation());
            }
        }
    }

    private void showUpdateScreen(String message) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(35, 35, 35, 35);
        layout.setBackgroundColor(Color.rgb(8, 13, 24));

        TextView title = new TextView(this);
        title.setText("WiFi Guard TR");
        title.setTextColor(Color.rgb(0, 220, 210));
        title.setTextSize(27);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText(message);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(10, 35, 10, 35);

        actionButton = new Button(this);
        actionButton.setText("LÜTFEN BEKLEYİN");

        layout.addView(title);
        layout.addView(statusText);
        layout.addView(actionButton);

        setContentView(layout);
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> {
            if (statusText != null) {
                statusText.setText(message);
            }
        });
    }

    private void openMainApp() {
        if (appOpened) return;

        appOpened = true;

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setBackgroundColor(0xFF080D18);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(
            new WifiBridge(), "AndroidWifi");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        requestWifiPermissions();
    }

    private void requestWifiPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;

        ArrayList<String> missing = new ArrayList<>();

        if (checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        if (!missing.isEmpty()) {
            requestPermissions(
                missing.toArray(new String[0]), 100
            );
        }
    }

    public class WifiBridge {

        @JavascriptInterface
        public String getWifiInfo() {
            org.json.JSONObject result =
                new org.json.JSONObject();

            try {
                WifiManager wifiManager =
                    (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

                ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(
                        Context.CONNECTIVITY_SERVICE);

                boolean connected = false;

                Network activeNetwork = cm.getActiveNetwork();
                NetworkCapabilities caps =
                    cm.getNetworkCapabilities(activeNetwork);

                if (caps != null) {
                    connected = caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI);
                }

                result.put("connected", connected);
                result.put("wifiEnabled",
                    wifiManager.isWifiEnabled());

                if (connected && wifiManager != null) {
                    WifiInfo info = wifiManager.getConnectionInfo();

                    String ssid = info.getSSID();

                    if (ssid != null) {
                        ssid = ssid.replace("\"", "");

                        if (ssid.equals("<unknown ssid>")) {
                            ssid = "SSID izni gerekli";
                        }
                    }

                    int rssi = info.getRssi();
                    int level =
                        WifiManager.calculateSignalLevel(rssi, 5);

                    int ip = info.getIpAddress();

                    String ipAddress = String.format(
                        "%d.%d.%d.%d",
                        ip & 0xff,
                        (ip >> 8) & 0xff,
                        (ip >> 16) & 0xff,
                        (ip >> 24) & 0xff
                    );

                    result.put("ssid", ssid);
                    result.put("rssi", rssi);
                    result.put("signalLevel", level);
                    result.put("ip", ipAddress);
                    result.put("linkSpeed", info.getLinkSpeed());
                    result.put("frequency", info.getFrequency());
                }

            } catch (Exception e) {
                try {
                    result.put("error", e.getMessage());
                } catch (Exception ignored) {}
            }


            return result.toString();
        }

        @JavascriptInterface
        public String scanWifiNetworks() {
            JSONObject output = new JSONObject();
            JSONArray networks = new JSONArray();

            try {
                if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    output.put("ok", false);
                    output.put("error", "LOCATION_PERMISSION");
                    return output.toString();
                }

                WifiManager manager =
                    (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

                if (manager == null || !manager.isWifiEnabled()) {
                    output.put("ok", false);
                    output.put("error", "WIFI_DISABLED");
                    return output.toString();
                }

                boolean started = manager.startScan();

                List<ScanResult> results = manager.getScanResults();

                for (ScanResult scan : results) {
                    JSONObject item = new JSONObject();

                    item.put("ssid",
                        scan.SSID == null || scan.SSID.isEmpty()
                        ? "(Gizli ağ)" : scan.SSID);

                    item.put("bssid",
                        scan.BSSID == null ? "" : scan.BSSID);

                    item.put("rssi", scan.level);
                    item.put("frequency", scan.frequency);
                    item.put("capabilities",
                        scan.capabilities == null
                        ? "" : scan.capabilities);

                    item.put("channel",
                        frequencyToChannel(scan.frequency));

                    item.put("band",
                        scan.frequency >= 5925 ? "6 GHz" :
                        scan.frequency >= 4900 ? "5 GHz" :
                        "2.4 GHz");

                    networks.put(item);
                }

                output.put("ok", true);
                output.put("scanStarted", started);
                output.put("networks", networks);
                output.put("count", networks.length());

            } catch (SecurityException e) {
                try {
                    output.put("ok", false);
                    output.put("error", "PERMISSION_DENIED");
                } catch (Exception ignored) {}
            } catch (Exception e) {
                try {
                    output.put("ok", false);
                    output.put("error", e.getMessage());
                } catch (Exception ignored) {}
            }

            return output.toString();
        }

        private int frequencyToChannel(int frequency) {
            if (frequency >= 2412 && frequency <= 2472)
                return (frequency - 2407) / 5;

            if (frequency == 2484) return 14;

            if (frequency >= 5000 && frequency < 5925)
                return (frequency - 5000) / 5;

            if (frequency >= 5955 && frequency <= 7115)
                return (frequency - 5950) / 5;

            return 0;
        }

        @JavascriptInterface
        public String scanLanDevices() {
            JSONObject output = new JSONObject();
            JSONArray devices = new JSONArray();

            ExecutorService executor = null;

            try {
                WifiManager manager =
                    (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

                if (manager == null || !manager.isWifiEnabled()) {
                    output.put("ok", false);
                    output.put("error", "WIFI_DISABLED");
                    return output.toString();
                }

                DhcpInfo dhcp = manager.getDhcpInfo();

                if (dhcp == null || dhcp.ipAddress == 0) {
                    output.put("ok", false);
                    output.put("error", "NO_LOCAL_NETWORK");
                    return output.toString();
                }

                int localIp = Integer.reverseBytes(dhcp.ipAddress);
                int gateway = Integer.reverseBytes(dhcp.gateway);
                int mask = dhcp.netmask == 0
                    ? 0xFFFFFF00
                    : Integer.reverseBytes(dhcp.netmask);

                String localAddress = intToIp(localIp);
                String gatewayAddress = intToIp(gateway);

                JSONObject self = new JSONObject();
                self.put("ip", localAddress);
                self.put("name", "Bu cihaz");
                self.put("type", "local");
                devices.put(self);

                output.put("localIp", localAddress);
                output.put("gateway", gatewayAddress);

                int hostBits = Integer.bitCount(~mask);
                int hostCount = (1 << Math.min(hostBits, 30)) - 2;
                hostCount = Math.max(0, Math.min(hostCount, 512));

                int base = localIp & mask;

                executor = Executors.newFixedThreadPool(32);
                List<Future<JSONObject>> futures = new ArrayList<>();

                for (int i = 1; i <= hostCount; i++) {
                    final int candidate = base + i;

                    if (candidate == localIp ||
                        candidate == gateway) {
                        continue;
                    }

                    futures.add(executor.submit(() -> {
                        try {
                            InetAddress address =
                                InetAddress.getByAddress(
                                    new byte[] {
                                        (byte) (candidate >>> 24),
                                        (byte) (candidate >>> 16),
                                        (byte) (candidate >>> 8),
                                        (byte) candidate
                                    });

                            if (address.isReachable(220)) {
                                JSONObject device = new JSONObject();
                                device.put("ip", address.getHostAddress());
                                device.put("name", "Ağ cihazı");
                                device.put("type", "discovered");
                                return device;
                            }
                        } catch (Exception ignored) {}

                        return null;
                    }));
                }

                if (gateway != 0) {
                    try {
                        InetAddress gw = InetAddress.getByAddress(
                            new byte[] {
                                (byte) (gateway >>> 24),
                                (byte) (gateway >>> 16),
                                (byte) (gateway >>> 8),
                                (byte) gateway
                            });

                        if (gw.isReachable(250)) {
                            JSONObject router = new JSONObject();
                            router.put("ip", gatewayAddress);
                            router.put("name", "Ağ geçidi / Router");
                            router.put("type", "router");
                            devices.put(router);
                        }
                    } catch (Exception ignored) {}
                }

                for (Future<JSONObject> future : futures) {
                    try {
                        JSONObject device =
                            future.get(1500, TimeUnit.MILLISECONDS);

                        if (device != null) devices.put(device);

                    } catch (Exception ignored) {}
                }

                output.put("ok", true);
                output.put("devices", devices);
                output.put("count", devices.length());
                output.put("scanLimited", hostCount >= 512);

            } catch (Exception e) {
                try {
                    output.put("ok", false);
                    output.put("error", e.getMessage());
                } catch (Exception ignored) {}
            } finally {
                if (executor != null) executor.shutdownNow();
            }

            return output.toString();
        }

        private String intToIp(int value) {
            return ((value >>> 24) & 255) + "." +
                   ((value >>> 16) & 255) + "." +
                   ((value >>> 8) & 255) + "." +
                   (value & 255);
        }
    }
}
