package com.example.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.broker.Message;
import com.example.broker.IMessageListener;
import com.example.broker.IMessagingService;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";

    // ===== 외부 브로커 접속 설정 =====
    // 디바이스에서 PC로 붙을 때는 PC의 실제 IP로 바꿔주세요.
    private static final String EXTERNAL_BROKER_URL = "tcp://192.168.0.6:1883";
    private static final String CLIENT_ID_MAIN = "AndroidGateway_" + System.currentTimeMillis();

    // ===== 재연결 설정 =====
    private static final long RECONNECT_DELAY = 5000L; // 5초

    private IMessagingService messagingService;
    private ServiceConnection brokerConnection;
    private boolean isConnected = false;

    private Handler reconnectHandler;
    private final Map<String, MqttClient> externalConnections = new ConcurrentHashMap<>();

    // ===== 무한 루프 방지를 위한 메시지 캐시 =====
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> externalOriginMessages = ConcurrentHashMap.newKeySet(); // 외부에서 온 메시지 추적
    private final Set<String> selfPublishedMessages = ConcurrentHashMap.newKeySet(); // 자기가 발행한 메시지 추적
    private static final int MAX_CACHE_SIZE = 1000; // 최대 캐시 크기

    @Override
    public void onCreate() {
        super.onCreate();
        reconnectHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "MqttGatewayService created");

        startForegroundServiceWithNotification();

        // 내부 브로커 바인드
        connectToBrokerService();

        // 내부→외부 전송용 외부 MQTT 연결
        connectToExternalMqtt();
    }

    private void startForegroundServiceWithNotification() {
        final String channelId = "gateway_service_channel";
        final String channelName = "Gateway Service Channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Gateway 서비스 동작 중")
                .setContentText("메시지 브로커와 연결됨")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MqttGatewayService started");
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MqttGatewayService destroyed");

        if (reconnectHandler != null) reconnectHandler.removeCallbacksAndMessages(null);

        if (brokerConnection != null) {
            try { unbindService(brokerConnection); } catch (Exception e) { Log.e(TAG, "Error unbinding", e); }
        }

        for (MqttClient c : externalConnections.values()) {
            try { if (c.isConnected()) c.disconnect(); c.close(); } catch (Exception ignored) {}
        }
        externalConnections.clear();
    }

    // ===== 내부 브로커 바인딩 =====
    private void connectToBrokerService() {
        if (isConnected) return;

        Log.d(TAG, "🔗 Connecting to broker service...");

        brokerConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "✅ Broker service connected");
                messagingService = IMessagingService.Stub.asInterface(service);
                isConnected = true;

                // 내부→외부 포워딩 리스너 등록
                registerGatewayListener();

                // 외부→내부 전달 시작
                startExternalToInternalBridge();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "❌ Broker service disconnected");
                messagingService = null;
                isConnected = false;
                scheduleReconnect();
            }
        };

        Intent brokerIntent = new Intent();
        brokerIntent.setComponent(new ComponentName("com.example.broker", "com.example.broker.MessagingService"));
        boolean bound = bindService(brokerIntent, brokerConnection, BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind to broker service");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectHandler == null) return;
        Log.d(TAG, "⏳ Scheduling reconnect in " + RECONNECT_DELAY + "ms");
        reconnectHandler.postDelayed(() -> {
            if (!isConnected) {
                Log.d(TAG, "🔄 Attempting to reconnect to broker service...");
                connectToBrokerService();
            }
        }, RECONNECT_DELAY);
    }

    // ===== 무한 루프 방지 헬퍼 메서드 =====
    private String generateMessageKey(String topic, byte[] payload) {
        // 토픽 + 페이로드 해시로 고유 키 생성
        String payloadHash = String.valueOf(java.util.Arrays.hashCode(payload));
        return topic + ":" + payloadHash;
    }

    private boolean isAlreadyProcessed(String messageKey) {
        if (processedMessages.contains(messageKey)) {
            return true;
        }

        // 캐시 크기 제한
        if (processedMessages.size() >= MAX_CACHE_SIZE) {
            processedMessages.clear(); // 간단한 방법: 전체 클리어
            Log.d(TAG, "🧹 Cleared message cache (reached max size)");
        }

        processedMessages.add(messageKey);
        return false;
    }

    // ===== 내부→외부: 브로커로부터 수신 → 외부로 전달 =====
    private void registerGatewayListener() {
        if (messagingService == null) return;
        try {
            IMessageListener gatewayListener = new IMessageListener.Stub() {
                @Override public void onMessage(Message message) throws RemoteException {
                    String topic = message.getTopic();
                    if (topic == null) {
                        Log.w(TAG, "drop: null topic from internal");
                        return;
                    }

                    // 외부에서 온 메시지인지 확인
                    String messageKey = generateMessageKey(topic, message.getPayload());
                    if (externalOriginMessages.contains(messageKey)) {
                        Log.d(TAG, "🚫 Skip forwarding external-origin message back to external: " + topic);
                        // 외부 출처 메시지 캐시에서 제거 (처리 완료)
                        externalOriginMessages.remove(messageKey);
                        return;
                    }

                    // 내부에서 발생한 메시지만 외부로 전달
                    Log.d(TAG, "Forward to external: " + topic);
                    forwardToExternal(topic, message.getPayload());
                }
            };

            // ❗ AIDL 메서드명은 프로젝트에 맞춰주세요.
            // 예: messagingService.registerGatewayListener(gatewayListener);
            messagingService.GatewayListener(gatewayListener);

            Log.i(TAG, "✅ Gateway listener registered to broker");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register gateway listener", e);
            isConnected = false;
            scheduleReconnect();
        }
    }

    // mqtt와 게이트웨이 간 통신 담당하는 코드
    private void forwardToExternal(String topic, byte[] payload) {
        // 중복 메시지 방지 체크
        String messageKey = generateMessageKey(topic, payload);
        if (isAlreadyProcessed(messageKey)) {
            Log.d(TAG, "🔄 Skip duplicate message to external: " + topic);
            return;
        }

        // 자기가 발행할 메시지임을 표시
        selfPublishedMessages.add(messageKey);

        MqttClient client = externalConnections.get("main");
        if (client == null || !client.isConnected()) {
            Log.w(TAG, "⚠️ External client not connected, cannot forward: " + topic);
            return;
        }
        try {
            if (payload == null) payload = new byte[0];
            MqttMessage out = new MqttMessage(payload);
            out.setQos(1);      // 고정값(변환 아님)
            out.setRetained(false);
            client.publish(topic, out); // publish 로 메시지 발행함 -> mqtt와는 p/s 프로토콜로 메시지를 받을 수 있기 때문에.. 단순히 리스너로는 불가능함
            // 한 번만 로그 출력
            Log.i(TAG, "📡 ✅ Forwarded to external: " + topic + " (" + payload.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to forward to external: " + topic, e);
        }
    }

    // ===== 외부 브로커 접속(내부→외부 전송용) =====
    private void connectToExternalMqtt() {
        new Thread(() -> {
            try {
                Log.d(TAG, "🌐 Connecting to external MQTT (main): " + EXTERNAL_BROKER_URL);
                MqttClient client = new MqttClient(EXTERNAL_BROKER_URL, CLIENT_ID_MAIN, new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setKeepAliveInterval(30);
                options.setConnectionTimeout(10);

                client.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        Log.w(TAG, "❌ External(main) connection lost", cause);
                        scheduleExternalReconnect();
                    }
                    @Override public void messageArrived(String topic, MqttMessage message) { /* not used here */ }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) {
                        // deliveryComplete 로그 제거 - forwardToExternal에서 이미 출력함
                    }
                });

                client.connect(options);

                // 기존 클라이언트 정리 후 교체
                MqttClient old = externalConnections.put("main", client);
                if (old != null) {
                    try { if (old.isConnected()) old.disconnect(); old.close(); } catch (Exception ignored) {}
                }
                Log.i(TAG, "✅ Connected to external MQTT (main)");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to connect external MQTT (main)", e);
                scheduleExternalReconnect();
            }
        }).start();
    }

    private void scheduleExternalReconnect() {
        if (reconnectHandler == null) return;
        Log.d(TAG, "⏳ Scheduling external MQTT reconnect in " + RECONNECT_DELAY + "ms");
        reconnectHandler.postDelayed(() -> {
            MqttClient c = externalConnections.get("main");
            if (c == null || !c.isConnected()) {
                Log.d(TAG, "🔄 Attempting to reconnect to external MQTT (main)...");
                connectToExternalMqtt();
            }
        }, RECONNECT_DELAY);
    }

    // ===== 외부→내부: 외부 MQTT의 모든 메시지를 내부로 전달 =====
    private void startExternalToInternalBridge() {
        if (messagingService == null) {
            Log.w(TAG, "⚠️ Cannot start external→internal bridge - messaging service not available");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "🌐 Starting external→internal bridge...");

                String inboundClientId = "AndroidGateway_Inbound_" + System.currentTimeMillis();
                MqttClient inboundClient = new MqttClient(EXTERNAL_BROKER_URL, inboundClientId, new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setKeepAliveInterval(30);
                options.setConnectionTimeout(10);

                inboundClient.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        Log.w(TAG, "❌ External→internal connection lost", cause);
                    }

                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        String messageKey = generateMessageKey(topic, mqttMessage.getPayload());

                        // 자기가 발행한 메시지인지 확인
                        if (selfPublishedMessages.contains(messageKey)) {
                            Log.d(TAG, "🚫 Skip self-published message from external: " + topic);
                            selfPublishedMessages.remove(messageKey); // 처리 완료된 메시지 제거
                            return;
                        }

                        // 중복 메시지 확인
                        if (isAlreadyProcessed(messageKey)) {
                            Log.d(TAG, "🔄 Skip duplicate message from external: " + topic);
                            return;
                        }

                        Log.d(TAG, "📨 External→internal (genuine external message): " + topic + " (" + (mqttMessage.getPayload() != null ? mqttMessage.getPayload().length : 0) + " bytes)");
                        forwardToInternal(topic, mqttMessage.getPayload());
                    }

                    @Override public void deliveryComplete(IMqttDeliveryToken token) { /* not used */ }
                });

                inboundClient.connect(options);
                // 모든 토픽을 받음 (#은 모든 토픽을 의미)
                inboundClient.subscribe("#", 1);

                // 기존 inbound 클라이언트 정리 후 교체
                MqttClient old = externalConnections.put("inbound", inboundClient);
                if (old != null) {
                    try { if (old.isConnected()) old.disconnect(); old.close(); } catch (Exception ignored) {}
                }

                Log.i(TAG, "✅ External→internal bridge ready - receiving ALL topics (#)");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to start external→internal bridge", e);
            }
        }).start();
    }

    private void forwardToInternal(String topic, byte[] payload) {
        // 중복 메시지 방지 체크
        String messageKey = generateMessageKey(topic, payload);
        if (isAlreadyProcessed(messageKey)) {
            Log.d(TAG, "🔄 Skip duplicate message to internal: " + topic);
            return;
        }

        // 외부에서 온 메시지임을 표시
        externalOriginMessages.add(messageKey);

        // 외부 출처 캐시 크기 제한
        if (externalOriginMessages.size() >= MAX_CACHE_SIZE) {
            externalOriginMessages.clear();
            Log.d(TAG, "🧹 Cleared external origin cache (reached max size)");
        }

        if (messagingService == null) {
            Log.w(TAG, "⚠️ Cannot forward to internal - messaging service not available");
            return;
        }
        try {
            if (payload == null) payload = new byte[0];

            Message msg = new Message(topic, payload);
            Log.d(TAG, "📤 Forwarding to internal broker: " + topic + " (" + payload.length + " bytes)");
            messagingService.publish(msg);
            Log.i(TAG, "📡 ✅ Forwarded to internal broker: " + topic);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to forward to internal: " + topic, e);
        }
    }

    public void stop() {
        Log.i(TAG, "🛑 Stopping MQTT Gateway Service");
        for (MqttClient client : externalConnections.values()) {
            try {
                if (client.isConnected()) client.disconnect();
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting external client", e);
            }
        }
        externalConnections.clear();
        Log.d(TAG, "Gateway service stopped");
    }
}
