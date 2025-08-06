package com.example.gateway;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GATEWAY";
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 간단한 레이아웃 생성
        statusText = new TextView(this);
        statusText.setText("Gateway 시작 중...");
        statusText.setPadding(50, 50, 50, 50);
        setContentView(statusText);

        Log.d(TAG, "🚀 Gateway onCreate 진입");

        // Gateway 서비스 시작 (Service로 변경)
        Intent intent = new Intent(this, MqttGatewayService.class);
        startService(intent);

        statusText.setText("Gateway 실행 중\n로그를 확인하세요");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 서비스 종료 필요시 아래 코드 사용
        Intent intent = new Intent(this, MqttGatewayService.class);
        stopService(intent);
    }
}