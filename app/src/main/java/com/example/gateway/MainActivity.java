package com.example.gateway;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GATEWAY";
    private static final String PREFS_NAME = "GatewayConfig";

    private EditText editBrokerUrl;
    private EditText editUplinkPattern;
    private EditText editDownlinkPattern;
    private Button btnStart;
    private Button btnStop;
    private TextView textStatus;

    private SharedPreferences prefs;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "Gateway MainActivity 시작");

        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        editBrokerUrl = findViewById(R.id.editBrokerUrl);
        editUplinkPattern = findViewById(R.id.editUplinkPattern);
        editDownlinkPattern = findViewById(R.id.editDownlinkPattern);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        textStatus = findViewById(R.id.textStatus);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void loadSettings() {
        // 기본값
        editBrokerUrl.setText(prefs.getString("brokerUrl", "tcp://192.168.0.6:1883"));
        editUplinkPattern.setText(prefs.getString("uplinkPattern", "uplink/app/+/out"));
        editDownlinkPattern.setText(prefs.getString("downlinkPattern", "downlink/app/+/in"));

        updateStatus("설정 로드 완료");
    }

    private void setupListeners() {
        btnStart.setOnClickListener(v -> startGatewayService());
        btnStop.setOnClickListener(v -> stopGatewayService());
    }

    private void startGatewayService() {
        if (isServiceRunning) {
            Toast.makeText(this, "서비스가 이미 실행 중입니다", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!validateInputs()) {
            return;
        }

        saveSettings();

        Intent intent = new Intent(this, MqttGatewayService.class);
        intent.putExtra("brokerUrl", editBrokerUrl.getText().toString().trim());
        intent.putExtra("uplinkPattern", editUplinkPattern.getText().toString().trim());
        intent.putExtra("downlinkPattern", editDownlinkPattern.getText().toString().trim());

        startService(intent);
        isServiceRunning = true;
        updateButtons();
        updateStatus("Gateway 서비스 시작됨");

        Log.d(TAG, "Gateway 서비스 시작 요청");
    }

    private void stopGatewayService() {
        Intent intent = new Intent(this, MqttGatewayService.class);
        stopService(intent);
        isServiceRunning = false;
        updateButtons();
        updateStatus("Gateway 서비스 정지됨");

        Log.d(TAG, "Gateway 서비스 정지 요청");
    }

    private boolean validateInputs() {
        String brokerUrl = editBrokerUrl.getText().toString().trim();
        String uplinkPattern = editUplinkPattern.getText().toString().trim();
        String downlinkPattern = editDownlinkPattern.getText().toString().trim();

        if (brokerUrl.isEmpty()) {
            editBrokerUrl.setError("브로커 URL을 입력하세요");
            return false;
        }

        if (!brokerUrl.startsWith("tcp://") && !brokerUrl.startsWith("ssl://")) {
            editBrokerUrl.setError("올바른 URL 형식이 아닙니다 (tcp:// 또는 ssl://)");
            return false;
        }

        if (uplinkPattern.isEmpty()) {
            editUplinkPattern.setError("Uplink 패턴을 입력하세요");
            return false;
        }

        if (downlinkPattern.isEmpty()) {
            editDownlinkPattern.setError("Downlink 패턴을 입력하세요");
            return false;
        }

        return true;
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("brokerUrl", editBrokerUrl.getText().toString().trim());
        editor.putString("uplinkPattern", editUplinkPattern.getText().toString().trim());
        editor.putString("downlinkPattern", editDownlinkPattern.getText().toString().trim());
        editor.apply();

        Toast.makeText(this, "설정이 저장되었습니다", Toast.LENGTH_SHORT).show();
    }

    private void updateButtons() {
        btnStart.setEnabled(!isServiceRunning);
        btnStop.setEnabled(isServiceRunning);
    }

    private void updateStatus(String status) {
        textStatus.setText(status);
        Log.d(TAG, "상태 업데이트: " + status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceRunning) {
            stopGatewayService();
        }
    }
}