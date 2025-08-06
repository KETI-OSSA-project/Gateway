package com.example.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.example.broker.MessagingManager;
import com.example.broker.Message;
import com.example.broker.Filter;
import com.example.broker.IMessageListener;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * MQTT Gateway Service that bridges internal broker and external MQTT brokers
 */
public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";

    private MessagingManager messagingManager;
    private Map<String, MqttClient> externalConnections;
    private Handler mainHandler;

    // Gateway configuration - PC의 실제 IP 주소 사용
    private static final String EXTERNAL_BROKER_URL = "tcp://192.168.0.6:1883";
    private static final String CLIENT_ID = "AzenaGateway_" + System.currentTimeMillis();

    // 실제 브로커 메시지 전달용 토픽만 남김
    private String[] outgoingTopics = {"sensor/data", "device/status"};
    private String[] incomingTopics = {"external/command", "external/config"};

    @Override
    public void onCreate() {
        super.onCreate();
        messagingManager = new MessagingManager();
        externalConnections = new ConcurrentHashMap<>();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "MqttGatewayService created");
        // 포그라운드 서비스로 전환
        startForegroundServiceWithNotification();
    }

    private void startForegroundServiceWithNotification() {
        String channelId = "gateway_service_channel";
        String channelName = "Gateway Service Channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Gateway 서비스 동작 중")
                .setContentText("메시지 브로커와 연결됨")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MqttGatewayService started");
        // 내부 브로커 연결만 실행
        new Thread(() -> {
            connectToInternalBroker();
        }).start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 바인딩이 필요 없으면 null 반환
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MqttGatewayService destroyed");
        // ServiceConnection leak 방지: MessagingManager disconnect 호출
        if (messagingManager != null) {
            messagingManager.disconnect();
        }
        // 리소스 정리 로직 작성
    }

    private void connectToInternalBroker() {
        Log.d(TAG, "🔗 Connecting to internal broker...");

        messagingManager = MessagingManager.getConnectedInstance(this, new MessagingManager.MessagingServiceConnectionListener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "✅ Internal broker connected");
                subscribeToOutgoingTopics();
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "❌ Internal broker disconnected");
            }
        });
    }

    private void subscribeToOutgoingTopics() {
        // 구독 기능 제거됨
    }

    private void forwardToExternal(String topic, byte[] payload) {
        MqttClient client = externalConnections.get("main");
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload);
                message.setQos(0);
                client.publish(topic, message);
                Log.i(TAG, "📡 ✅ Forwarded to external: " + topic + " (" + payload.length + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to forward to external: " + topic, e);
            }
        } else {
            Log.w(TAG, "⚠️ External client not connected, cannot forward: " + topic);
        }
    }

    private void forwardToInternal(String topic, byte[] payload) {
        if (messagingManager != null) {
            try {
                Message message = new Message(topic, payload, String.valueOf(System.currentTimeMillis()));
                messagingManager.publish(message);
                Log.i(TAG, "📡 ✅ Forwarded to internal: " + topic + " (" + payload.length + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to forward to internal: " + topic, e);
            }
        } else {
            Log.w(TAG, "⚠️ Internal broker not connected, cannot forward: " + topic);
        }
    }

    public void stop() {
        Log.i(TAG, "🛑 Stopping MQTT Gateway Service");

        // Disconnect from external brokers
        for (MqttClient client : externalConnections.values()) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting external client", e);
            }
        }
        externalConnections.clear();

        // Disconnect from internal broker
        if (messagingManager != null) {
            messagingManager.disconnect();
        }
    }
}
