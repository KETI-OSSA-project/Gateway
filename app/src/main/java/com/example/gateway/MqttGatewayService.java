package com.example.gateway;

import android.app.Service;
import android.content.Intent;
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
        // 추가 초기화 로직 필요시 여기에 작성
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MqttGatewayService started");
        // 서비스 시작 시 필요한 로직 작성
        // 예: MQTT 연결 시작 등
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
        // Subscribe to topics that should be forwarded to external MQTT
        for (String topic : outgoingTopics) {
            try {
                // Broker와 동일한 Builder 패턴 사용
                Filter filter = new Filter.Builder()
                    .setTopic(topic)
                    .build();

                messagingManager.subscribe(new IMessageListener.Stub() {
                    @Override
                    public void onMessage(Message message) {
                        Log.i(TAG, "📤 [OUTGOING] " + message.getTopic() + " -> " + new String(message.getPayload()));
                        forwardToExternal(message.getTopic(), message.getPayload());
                    }
                }, filter);

                Log.d(TAG, "🔔 Subscribed to outgoing topic: " + topic);
            } catch (Exception e) {
                Log.e(TAG, "Failed to subscribe to topic: " + topic, e);
            }
        }
    }

    private void connectToExternalBroker() {
        Log.d(TAG, "🌐 Connecting to external MQTT broker: " + EXTERNAL_BROKER_URL);

        try {
            MqttClient client = new MqttClient(EXTERNAL_BROKER_URL, CLIENT_ID, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
            // 인증이 필요한 경우 추가
            // options.setUserName("username");
            // options.setPassword("password".toCharArray());

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    Log.e(TAG, "🔌 External MQTT connection lost", cause);
                    // Attempt to reconnect after 5 seconds
                    mainHandler.postDelayed(() -> connectToExternalBroker(), 5000);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    Log.i(TAG, "📥 [INCOMING] " + topic + " -> " + new String(message.getPayload()));
                    forwardToInternal(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    Log.d(TAG, "📨 External delivery complete");
                }
            });

            client.connect(options);
            externalConnections.put("main", client);

            Log.i(TAG, "✅ External MQTT broker connected successfully");

            // Subscribe to incoming topics
            subscribeToIncomingTopics(client);

            // Send test message to confirm connection
            sendTestMessage(client);

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to connect to external MQTT broker", e);
            // Retry after delay
            mainHandler.postDelayed(() -> connectToExternalBroker(), 10000);
        }
    }

    private void subscribeToIncomingTopics(MqttClient client) {
        for (String topic : incomingTopics) {
            try {
                client.subscribe(topic, 0);
                Log.d(TAG, "🔔 Subscribed to incoming topic: " + topic);
            } catch (Exception e) {
                Log.e(TAG, "Failed to subscribe to external topic: " + topic, e);
            }
        }
    }

    private void sendTestMessage(MqttClient client) {
        try {
            String testTopic = "gateway/status";
            String testPayload = "Azena Gateway Connected at " + System.currentTimeMillis();
            MqttMessage message = new MqttMessage(testPayload.getBytes());
            message.setQos(0);
            client.publish(testTopic, message);
            Log.i(TAG, "📡 Test message sent: " + testTopic);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send test message", e);
        }
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
