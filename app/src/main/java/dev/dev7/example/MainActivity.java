package dev.dev7.example;

import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DOWNLOAD_SPEED_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DOWNLOAD_TRAFFIC_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_DURATION_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_UPLOAD_SPEED_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_UPLOAD_TRAFFIC_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import dev.dev7.lib.v2ray.V2rayController;
import dev.dev7.lib.v2ray.utils.V2rayConfigs;
import dev.dev7.lib.v2ray.utils.V2rayConstants;

public class MainActivity extends AppCompatActivity {

    private static final long SWITCH_THRESHOLD_BYTES = 1024 * 1024; // 1MB

    private Button connection;
    private TextView connection_speed, connection_traffic, connection_time,
            server_delay, connected_server_delay, connection_mode, core_version;

    private EditText v2ray_config;
    private SharedPreferences sharedPreferences;
    private BroadcastReceiver v2rayBroadCastReceiver;

    private boolean usingBootstrap = false;
    private boolean switchedToUserConfig = false;

    @SuppressLint({"SetTextI18n", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            V2rayController.init(this, R.drawable.ic_launcher, "V2ray Android");

            connection = findViewById(R.id.btn_connection);
            connection_speed = findViewById(R.id.connection_speed);
            connection_time = findViewById(R.id.connection_duration);
            connection_traffic = findViewById(R.id.connection_traffic);
            server_delay = findViewById(R.id.server_delay);
            connection_mode = findViewById(R.id.connection_mode);
            connected_server_delay = findViewById(R.id.connected_server_delay);
            v2ray_config = findViewById(R.id.v2ray_config);
            core_version = findViewById(R.id.core_version);
        }

        // Always proxy-only
        V2rayController.forceProxyMode();

        core_version.setText(V2rayController.getCoreVersion());

        sharedPreferences = getSharedPreferences("conf", MODE_PRIVATE);
        v2ray_config.setText(sharedPreferences.getString("v2ray_config", getDefaultConfig()));

        // show fixed local proxy
        connection_mode.setText("connection mode : PROXY_MODE (127.0.0.1:2090)");

        connection.setOnClickListener(view -> {
            sharedPreferences.edit()
                    .putString("v2ray_config", v2ray_config.getText().toString())
                    .apply();

            if (V2rayController.getConnectionState() == V2rayConstants.CONNECTION_STATES.DISCONNECTED) {
                startBootstrapConnection();
            } else {
                usingBootstrap = false;
                switchedToUserConfig = false;
                V2rayController.stopV2ray(this);
            }
        });

        connected_server_delay.setOnClickListener(view -> {
            connected_server_delay.setText("connected server delay : measuring...");
            V2rayController.getConnectedV2rayServerDelay(this, delayResult ->
                    runOnUiThread(() ->
                            connected_server_delay.setText("connected server delay : " + delayResult + "ms")));
        });

        server_delay.setOnClickListener(view -> {
            server_delay.setText("server delay : measuring...");
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    server_delay.setText("server delay : " +
                            V2rayController.getV2rayServerDelay(v2ray_config.getText().toString()) + "ms"), 200);
        });

        // disable mode switching in UI
        connection_mode.setOnClickListener(view ->
                connection_mode.setText("connection mode : PROXY_MODE (127.0.0.1:2090)"));

        switch (V2rayController.getConnectionState()) {
            case CONNECTED:
                connection.setText("CONNECTED");
                connected_server_delay.callOnClick();
                break;
            case DISCONNECTED:
                connection.setText("DISCONNECTED");
                break;
            case CONNECTING:
                connection.setText("CONNECTING");
                break;
            default:
                break;
        }

        v2rayBroadCastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(() -> {
                    Bundle extras = intent.getExtras();
                    if (extras == null) return;

                    connection_time.setText("connection time : " +
                            extras.getString(SERVICE_DURATION_BROADCAST_EXTRA));

                    connection_speed.setText("connection speed : " +
                            extras.getString(SERVICE_UPLOAD_SPEED_BROADCAST_EXTRA) + " | " +
                            extras.getString(SERVICE_DOWNLOAD_SPEED_BROADCAST_EXTRA));

                    String uploadTraffic = extras.getString(SERVICE_UPLOAD_TRAFFIC_BROADCAST_EXTRA, "0 B");
                    String downloadTraffic = extras.getString(SERVICE_DOWNLOAD_TRAFFIC_BROADCAST_EXTRA, "0 B");

                    connection_traffic.setText("connection traffic : " +
                            uploadTraffic + " | " + downloadTraffic);

                    if (usingBootstrap && !switchedToUserConfig) {
                        long downloadedBytes = parseTrafficToBytes(downloadTraffic);
                        if (downloadedBytes >= SWITCH_THRESHOLD_BYTES) {
                            switchedToUserConfig = true;
                            switchToUserConfig();
                            return;
                        } else {
                            long remain = SWITCH_THRESHOLD_BYTES - downloadedBytes;
                            connected_server_delay.setText(
                                    "bootstrap active : waiting for 1MB, remaining " + humanReadable(remain)
                            );
                        }
                    }

                    connection_mode.setText("connection mode : PROXY_MODE (127.0.0.1:2090)");

                    switch ((V2rayConstants.CONNECTION_STATES)
                            Objects.requireNonNull(extras.getSerializable(SERVICE_CONNECTION_STATE_BROADCAST_EXTRA))) {
                        case CONNECTED:
                            connection.setText("CONNECTED");
                            if (usingBootstrap && !switchedToUserConfig) {
                                connected_server_delay.setText("bootstrap connected : waiting for 1MB...");
                            }
                            break;
                        case DISCONNECTED:
                            connection.setText("DISCONNECTED");
                            connected_server_delay.setText("connected server delay : wait for connection");
                            break;
                        case CONNECTING:
                            connection.setText("CONNECTING");
                            break;
                        default:
                            break;
                    }
                });
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(v2rayBroadCastReceiver,
                    new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT),
                    RECEIVER_EXPORTED);
        } else {
            registerReceiver(v2rayBroadCastReceiver,
                    new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT));
        }
    }

    private void startBootstrapConnection() {
        String bootstrapConfig = readAssetText("bootstrap_config.json");
        if (bootstrapConfig == null || bootstrapConfig.trim().isEmpty()) {
            connected_server_delay.setText("bootstrap config not found in assets");
            return;
        }

        usingBootstrap = true;
        switchedToUserConfig = false;

        V2rayController.forceProxyMode();
        connected_server_delay.setText("connecting with initial profile...");
        V2rayController.startV2ray(this, "Initial Profile", bootstrapConfig, null);
    }

    private void switchToUserConfig() {
        String userConfig = v2ray_config.getText().toString().trim();
        if (userConfig.isEmpty()) {
            connected_server_delay.setText("user config is empty");
            return;
        }

        connected_server_delay.setText("initial profile complete. switching to your profile...");

        V2rayController.stopV2ray(this);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            V2rayController.forceProxyMode();
            V2rayController.startV2ray(this, "User Profile", userConfig, null);
            usingBootstrap = false;
            connected_server_delay.setText("connected using your profile on 127.0.0.1:2090");
        }, 1200);
    }

    private String readAssetText(String fileName) {
        try (InputStream is = getAssets().open(fileName);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    private long parseTrafficToBytes(String value) {
        try {
            String text = value.trim().toUpperCase(Locale.US)
                    .replace("/S", "")
                    .replace("IB", "B");

            String[] parts = text.split("\\s+");
            if (parts.length < 2) return 0L;

            double number = Double.parseDouble(parts[0]);
            String unit = parts[1];

            switch (unit) {
                case "B":
                    return (long) number;
                case "KB":
                    return (long) (number * 1024);
                case "MB":
                    return (long) (number * 1024 * 1024);
                case "GB":
                    return (long) (number * 1024 * 1024 * 1024);
                default:
                    return 0L;
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    public static String getDefaultConfig() {
        return "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (v2rayBroadCastReceiver != null) {
            unregisterReceiver(v2rayBroadCastReceiver);
        }
    }
}
