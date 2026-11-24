package com.wifiguard.tr;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import android.os.Build;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setBackgroundColor(0xFF080D18);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WifiBridge(), "AndroidWifi");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        requestWifiPermissions();
    }

    private void requestWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    100
                );
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                    101
                );
            }
        }
    }

    public class WifiBridge {

        @JavascriptInterface
        public String getWifiInfo() {
            JSONObject result = new JSONObject();

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
                result.put("wifiEnabled", wifiManager.isWifiEnabled());

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
                    int level = WifiManager.calculateSignalLevel(rssi, 5);

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
    }
}
