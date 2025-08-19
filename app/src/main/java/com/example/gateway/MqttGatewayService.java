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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";

    // ===== 외부 브로커 접속 설정 =====
    // 디바이스에서 PC로 붙을 때는 PC의 실제 IP로 바꿔야 함.
    private static final String EXTERNAL_BROKER_URL = "tcp://192.168.0.6:1883";

    // ===== 재연결 설정 =====
    private static final long RECONNECT_DELAY = 5000L; // 5초
    private static final long MAX_RECONNECT_DELAY = 60000L; // 최대 60초
    private int uplinkReconnectAttempts = 0;
    private int downlinkReconnectAttempts = 0;

    private IMessagingService messagingService;
    private ServiceConnection brokerConnection;
    private boolean isConnected = false;

    private Handler reconnectHandler;
    private final Map<String, MqttClient> externalConnections = new ConcurrentHashMap<>();
    private final ExecutorService connectionExecutor = Executors.newFixedThreadPool(2);

    // ===== 무한 루프 방지를 위한 메시지 캐시 =====
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> externalOriginMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> selfPublishedMessages = ConcurrentHashMap.newKeySet();
    private static final int MAX_CACHE_SIZE = 1000;

    // ===== 설정 모델 도입 =====
    private static class GatewayConfig {
        String externalBrokerUrl;
        String uplinkPattern;        // 내부→외부 허용 패턴 (필터링 + 매핑용)
        String downlinkPattern;      // 외부→내부 구독 패턴

        // 패턴 캐싱
        private Pattern compiledUplinkPattern;
        private Pattern compiledDownlinkPattern;

        void compilePatterns() {
            if (uplinkPattern != null) {
                compiledUplinkPattern = compileTopicPattern(uplinkPattern);
            }
            if (downlinkPattern != null) {
                compiledDownlinkPattern = compileTopicPattern(downlinkPattern);
            }
        }

        private Pattern compileTopicPattern(String pattern) {
            String regex = "^" + pattern.replace(".", "\\.").replace("+", "[^/]+") + "$";
            return Pattern.compile(regex);
        }
    }
    private GatewayConfig config;

    @Override
    public void onCreate() {
        super.onCreate();
        reconnectHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "MqttGatewayService created");

        startForegroundServiceWithNotification();

        // 설정 로드 (SharedPreferences에서)
        config = loadConfigFromPreferences();

        // 내부 브로커 바인드 (연결 후 applyConfig 호출)
        connectToBrokerService();
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MqttGatewayService started");

        // Intent에서 설정 업데이트가 있는지 확인
        if (intent != null && intent.hasExtra("brokerUrl")) {
            GatewayConfig newConfig = new GatewayConfig();
            newConfig.externalBrokerUrl = intent.getStringExtra("brokerUrl");
            newConfig.uplinkPattern = intent.getStringExtra("uplinkPattern");
            newConfig.downlinkPattern = intent.getStringExtra("downlinkPattern");

            newConfig.compilePatterns();

            // 항상 설정을 적용 (기존 설정과 다를 때만이 아니라)
            Log.i(TAG, "🔧 UI에서 설정 받음, 강제 적용");
            Log.i(TAG, "🔧 새 설정: uplink=" + newConfig.uplinkPattern);
            config = newConfig;
            saveConfigToPreferences(config);

            if (isConnected) {
                // 게이트웨이 리스너를 다시 등록 (새로운 설정으로)
                registerGatewayListener();
                applyConfig(config);
            }
        } else {
            // Intent에 설정이 없으면 기존 설정 다시 로드
            Log.i(TAG, "🔧 Intent에 설정 없음, 기존 설정 재로드");
            config = loadConfigFromPreferences();
        }

        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MqttGatewayService destroyed");

        if (reconnectHandler != null) reconnectHandler.removeCallbacksAndMessages(null);
        if (connectionExecutor != null) connectionExecutor.shutdown();

        if (brokerConnection != null) {
            try { unbindService(brokerConnection); } catch (Exception e) { Log.e(TAG, "Error unbinding", e); }
        }

        for (MqttClient c : externalConnections.values()) {
            try { if (c.isConnected()) c.disconnect(); c.close(); } catch (Exception ignored) {}
        }
        externalConnections.clear();
    }

    // ===== 설정 로딩 (SharedPreferences 기반) =====
    private GatewayConfig loadConfigFromPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences("GatewayConfig", MODE_PRIVATE);

        // 디버깅: 저장된 모든 값 출력
        Log.d(TAG, "🔍 저장된 설정값들:");
        Log.d(TAG, "  brokerUrl: " + prefs.getString("brokerUrl", "없음"));
        Log.d(TAG, "  uplinkPattern: " + prefs.getString("uplinkPattern", "없음"));
        Log.d(TAG, "  downlinkPattern: " + prefs.getString("downlinkPattern", "없음"));

        GatewayConfig cfg = new GatewayConfig();
        cfg.externalBrokerUrl = prefs.getString("brokerUrl", EXTERNAL_BROKER_URL);
        cfg.uplinkPattern = prefs.getString("uplinkPattern", "uplink/app/+/out");
        cfg.downlinkPattern = prefs.getString("downlinkPattern", "downlink/app/+/in");

        cfg.compilePatterns();
        Log.i(TAG, "📋 설정 로드: " + cfg.externalBrokerUrl + ", uplink=" + cfg.uplinkPattern + ", downlink=" + cfg.downlinkPattern);
        return cfg;
    }

    private void saveConfigToPreferences(GatewayConfig cfg) {
        android.content.SharedPreferences.Editor editor = getSharedPreferences("GatewayConfig", MODE_PRIVATE).edit();
        editor.putString("brokerUrl", cfg.externalBrokerUrl);
        editor.putString("uplinkPattern", cfg.uplinkPattern);
        editor.putString("downlinkPattern", cfg.downlinkPattern);
        editor.apply();
    }

    private boolean configEquals(GatewayConfig a, GatewayConfig b) {
        if (a == null || b == null) return false;
        return java.util.Objects.equals(a.externalBrokerUrl, b.externalBrokerUrl) &&
               java.util.Objects.equals(a.uplinkPattern, b.uplinkPattern) &&
               java.util.Objects.equals(a.downlinkPattern, b.downlinkPattern);
    }

    // ===== 설정 적용 =====
    private synchronized void applyConfig(GatewayConfig cfg) {
        Log.i(TAG, "🔧 Applying config");
        teardownExternalConnections();
        connectExternalForUplink(cfg);
        connectExternalForDownlink(cfg);
    }

    private void teardownExternalConnections() {
        for (MqttClient c : externalConnections.values()) {
            try { if (c.isConnected()) c.disconnect(); c.close(); } catch (Exception ignored) {}
        }
        externalConnections.clear();
    }

    // 내부 -> 외부 (uplink)
    private void connectExternalForUplink(GatewayConfig cfg) {
        connectionExecutor.execute(() -> {
            try {
                String id = "AndroidGateway_Uplink_" + System.currentTimeMillis();
                Log.d(TAG, "🌐 Connecting uplink: " + cfg.externalBrokerUrl);
                MqttClient up = new MqttClient(cfg.externalBrokerUrl, id, new MemoryPersistence());
                MqttConnectOptions opt = new MqttConnectOptions();
                opt.setAutomaticReconnect(true);
                opt.setCleanSession(true);
                opt.setKeepAliveInterval(30);
                opt.setConnectionTimeout(10);
                up.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        Log.w(TAG, "❌ Uplink lost", cause);
                        scheduleUplinkReconnect();
                    }
                    @Override public void messageArrived(String topic, MqttMessage message) { /* publish-only */ }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                up.connect(opt);
                MqttClient old = externalConnections.put("uplink", up);
                if (old != null) { try { if (old.isConnected()) old.disconnect(); old.close(); } catch (Exception ignored) {} }
                uplinkReconnectAttempts = 0; // 성공 시 리셋
                Log.i(TAG, "✅ Uplink connected (publish-only)");
            } catch (Exception e) {
                Log.e(TAG, "❌ Uplink connect failed", e);
                scheduleUplinkReconnect();
            }
        });
    }
    // 외부 -> 내부 (downlink)
    private void connectExternalForDownlink(GatewayConfig cfg) {
        connectionExecutor.execute(() -> {
            try {
                String id = "AndroidGateway_Downlink_" + System.currentTimeMillis();
                Log.d(TAG, "🌐 Connecting downlink: " + cfg.externalBrokerUrl);
                MqttClient down = new MqttClient(cfg.externalBrokerUrl, id, new MemoryPersistence());
                MqttConnectOptions opt = new MqttConnectOptions();
                opt.setAutomaticReconnect(true);
                opt.setCleanSession(true);
                opt.setKeepAliveInterval(30);
                opt.setConnectionTimeout(10);
                down.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        Log.w(TAG, "❌ Downlink lost", cause);
                        scheduleDownlinkReconnect();
                    }
                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        if (!topicMatches(config.compiledDownlinkPattern, topic)) {
                            Log.d(TAG, "skip external(not allowed): " + topic);
                            return;
                        }
                        String internal = mapDownlinkToInternal(topic);
                        forwardToInternal(internal, mqttMessage.getPayload());
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                down.connect(opt);
                down.subscribe(config.downlinkPattern, 1);
                MqttClient old = externalConnections.put("downlink", down);
                if (old != null) { try { if (old.isConnected()) old.disconnect(); old.close(); } catch (Exception ignored) {} }
                downlinkReconnectAttempts = 0; // 성공 시 리셋
                Log.i(TAG, "✅ Downlink connected & subscribed: " + config.downlinkPattern);
            } catch (Exception e) {
                Log.e(TAG, "❌ Downlink connect failed", e);
                scheduleDownlinkReconnect();
            }
        });
    }

    // 재연결 관리하는 코드들
    private void scheduleUplinkReconnect() {
        if (reconnectHandler == null) return;
        long delay = Math.min(RECONNECT_DELAY * (1L << uplinkReconnectAttempts), MAX_RECONNECT_DELAY);
        uplinkReconnectAttempts++;
        Log.d(TAG, "⏳ Scheduling uplink reconnect in " + delay + "ms (attempt " + uplinkReconnectAttempts + ")");
        reconnectHandler.postDelayed(() -> {
            MqttClient c = externalConnections.get("uplink");
            if (c == null || !c.isConnected()) connectExternalForUplink(config);
        }, delay);
    }

    private void scheduleDownlinkReconnect() {
        if (reconnectHandler == null) return;
        long delay = Math.min(RECONNECT_DELAY * (1L << downlinkReconnectAttempts), MAX_RECONNECT_DELAY);
        downlinkReconnectAttempts++;
        Log.d(TAG, "⏳ Scheduling downlink reconnect in " + delay + "ms (attempt " + downlinkReconnectAttempts + ")");
        reconnectHandler.postDelayed(() -> {
            MqttClient c = externalConnections.get("downlink");
            if (c == null || !c.isConnected()) connectExternalForDownlink(config);
        }, delay);
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

                // 내부→외부 시작
                registerGatewayListener();

                // 외부→내부 시작
                // startExternalToInternalBridge(); // 제거됨
                applyConfig(config);       // uplink / downlink 연결
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
                    if (topic == null) return;

                    // 디버깅용 로그 추가
                    Log.d(TAG, "🔍 받은 내부 토픽: " + topic);
                    Log.d(TAG, "🔍 내부 허용 패턴: " + config.uplinkPattern);
                    Log.d(TAG, "🔍 패턴 컴파일됨: " + (config.compiledUplinkPattern != null));

                    if (config.compiledUplinkPattern != null &&
                        !topicMatches(config.compiledUplinkPattern, topic)) {
                        Log.d(TAG, "skip internal(not allowed): " + topic);
                        return;
                    }

                    Log.d(TAG, "✅ 내부 토픽 허용됨: " + topic);
                    String outTopic = mapInternalToUplink(topic);
                    Log.d(TAG, "🔄 매핑된 외부 토픽: " + outTopic);
                    forwardToExternal(outTopic, message.getPayload());
                }
            };
            messagingService.GatewayListener(gatewayListener);
            Log.i(TAG, "✅ Gateway listener registered");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register gateway listener", e);
            isConnected = false;
            scheduleReconnect();
        }
    }

    // mqtt와 게이트웨이 간 통신 담당하는 코드
    private void forwardToExternal(String topic, byte[] payload) {
        String messageKey = generateMessageKey(topic, payload);
        if (isAlreadyProcessed(messageKey)) return;

        // 자기가 발행할 메시지임을 표시
        selfPublishedMessages.add(messageKey);

        MqttClient client = externalConnections.get("uplink");
        if (client == null || !client.isConnected()) {
            Log.w(TAG, "uplink not connected: " + topic);
            return;
        }
        try {
            if (payload == null) payload = new byte[0];
            MqttMessage out = new MqttMessage(payload);
            out.setQos(1); out.setRetained(false);
            client.publish(topic, out);
            Log.i(TAG, "📡 → external(uplink): " + topic);
        } catch (Exception e) {
            Log.e(TAG, "forwardToExternal error: " + topic, e);
        }
    }

    // ===== 토픽 유틸 (패턴 캐싱 버전) =====
    private boolean topicMatches(Pattern compiledPattern, String topic) {
        if (compiledPattern == null || topic == null) return false;
        return compiledPattern.matcher(topic).matches();
    }

    private String mapInternalToUplink(String internalTopic) {
        // UI에서 설정한 패턴에 따라 동적 매핑
        if (config.compiledUplinkPattern == null ||
            !topicMatches(config.compiledUplinkPattern, internalTopic)) {
            return internalTopic; // 패턴 불일치 시 그대로
        }

        // 간단한 접두어 교체 (더 정교한 매핑 필요 시 확장 가능)
        // 예: uplink/app/+/out 패턴이면 그대로, app/+/out 패턴이면 uplink/ 추가
        if (config.uplinkPattern != null && config.uplinkPattern.toLowerCase().startsWith("uplink/")) {
            return internalTopic; // 이미 uplink로 시작하는 패턴이면 그대로
        } else {
            // uplink/ 접두어 추가
            return "uplink/" + internalTopic;
        }
    }

    private String mapDownlinkToInternal(String downlinkTopic) {
        if (downlinkTopic == null) return "";

        // UI에서 설정한 downlink 패턴에 따라 동적 매핑
        if (config.downlinkPattern != null && config.downlinkPattern.toLowerCase().startsWith("downlink/")) {
            return downlinkTopic.replaceFirst("^[Dd]ownlink/", "");
        } else {
            // downlink/ 접두어가 없는 패턴이면 그대로
            return downlinkTopic;
        }
    }

    private void forwardToInternal(String topic, byte[] payload) {
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
            messagingService.publish(msg);
            Log.i(TAG, "📡 ← internal(downlink): " + topic);
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
