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
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import java.util.Properties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";
    private static final long RECONNECT_DELAY = 5000L;
    // 접근 A: 브리지가 붙는 대상은 이제 앱 내장 브로커(자기 자신). MainActivity의 브로커 URL 필드는 사용 안 함.
    private static final String LOCAL_BROKER_URL = "tcp://127.0.0.1:1883";
    private static final long MAX_RECONNECT_DELAY = 60000L;
    private static final int MAX_CACHE_SIZE = 1000;

    private int uplinkReconnectAttempts = 0;
    private int downlinkReconnectAttempts = 0;

    private IMessagingService messagingService;
    private ServiceConnection brokerConnection;
    private boolean isConnected = false;

    private Handler reconnectHandler;
    private final Map<String, MqttClient> externalConnections = new ConcurrentHashMap<>();  // "uplink", "downlink" key로, 참조값을 value로 저장
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();

    private Server embeddedBroker;   // 스파이크: 앱 내장 MQTT 브로커 (구 PC Mosquitto 대체)
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();

    // gateway 기본 설정
    private static class GatewayConfig {
        String externalBrokerUrl;
        String uplinkPattern;
        String downlinkPattern;


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
        startForegroundServiceWithNotification();
        startEmbeddedBroker();
        config = loadConfigFromPreferences();
        connectToBrokerService();
    }

    // 게이트 2 스파이크: 내장 브로커가 폰에서 뜨고 1883을 리슨하는지 확인
    private void startEmbeddedBroker() {
        try {
            Properties props = new Properties();
            props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, "0.0.0.0");
            props.setProperty(BrokerConstants.PORT_PROPERTY_NAME, "1883");
            props.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
            props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME, "");  // 빈 값 = H2 파일 저장 끄고 in-memory
            IConfig cfg = new MemoryConfig(props);

            Server broker = new Server();
            broker.startServer(cfg);
            embeddedBroker = broker;
            Log.i(TAG, "✅ 내장 브로커 기동 성공: 0.0.0.0:1883 리슨");
        } catch (Throwable t) {
            Log.e(TAG, "❌ 내장 브로커 기동 실패", t);
        }
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
                .setContentText("내부 브로커와 연결됨")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "게이트웨이 서비스 시작");

        if (intent != null && intent.getBooleanExtra("clearCache", false)) {
            processedMessages.clear();
            Log.i(TAG, "캐시 청소");
            return START_STICKY;
        }

        // Intent에서 설정 업데이트가 있는지 확인
        if (intent != null && intent.hasExtra("brokerUrl")) {
            GatewayConfig newConfig = new GatewayConfig();
            newConfig.externalBrokerUrl = intent.getStringExtra("brokerUrl");
            newConfig.uplinkPattern = intent.getStringExtra("uplinkPattern");
            newConfig.downlinkPattern = intent.getStringExtra("downlinkPattern");

            newConfig.compilePatterns();

            Log.i(TAG, "새 설정: uplink=" + newConfig.uplinkPattern + ", downlink=" + newConfig.downlinkPattern );
            config = newConfig;
            saveConfigToPreferences(config);

            if (isConnected) {
                registerGatewayListener();
                applyConfig(config);
            }
        } else {
            // Intent에 설정이 없으면 기존 설정 다시 로드
            Log.i(TAG, "기존 설정 재로드");
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

        if (embeddedBroker != null) {
            try { embeddedBroker.stopServer(); } catch (Exception ignored) {}
        }
    }

    private GatewayConfig loadConfigFromPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences("GatewayConfig", MODE_PRIVATE);

        GatewayConfig cfg = new GatewayConfig();
        cfg.externalBrokerUrl = prefs.getString("brokerUrl", "");
        cfg.uplinkPattern = prefs.getString("uplinkPattern", "uplink/app/+/out");
        cfg.downlinkPattern = prefs.getString("downlinkPattern", "downlink/app/+/in");
        cfg.compilePatterns();
        Log.i(TAG, "📋 설정 로드: " + cfg.externalBrokerUrl + ", uplink=" + cfg.uplinkPattern + ", downlink=" + cfg.downlinkPattern );
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

    private synchronized void applyConfig(GatewayConfig cfg) {
        Log.i(TAG, "🔧 Applying config");
        teardownExternalConnections();
        connectExternalForUplink(cfg);
        connectExternalForDownlink(cfg);
    }

    private void teardownExternalConnections() {
        for (MqttClient c : externalConnections.values()) {
            closeQuietly(c);
        }
        externalConnections.clear();
    }

    // 유령 재접속 방지: isConnected()로 가드하면 '자동 재접속 중(끊김 상태)'인 클라이언트의
    // disconnect를 건너뛰어 automaticReconnect 스레드가 살아남는다. disconnectForcibly()는
    // 연결 상태와 무관하게 재접속까지 강제로 멈추므로 무조건 호출한다.
    private void closeQuietly(MqttClient c) {
        if (c == null) return;
        try { c.disconnectForcibly(0L, 0L); } catch (Exception ignored) {}
        try { c.close(true); } catch (Exception ignored) {}
    }

    // 내부 -> 외부 (uplink)
    private void connectExternalForUplink(GatewayConfig cfg) {
        connectionExecutor.execute(() -> {
            try {
                closeQuietly(externalConnections.remove("uplink"));   // 기존 클라이언트 정리 (유령 연결 방지)
                String id = "AndroidGateway_Uplink";                  // 고정 ID: 재접속 시 브로커가 옛 연결을 자동 정리
                Log.d(TAG, "uplink 클라이언트 연결");
                MqttClient up = new MqttClient(LOCAL_BROKER_URL, id, new MemoryPersistence());
                MqttConnectOptions opt = new MqttConnectOptions();
                opt.setAutomaticReconnect(true);
                opt.setCleanSession(true);
                opt.setKeepAliveInterval(30);
                opt.setConnectionTimeout(10);
                up.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        // Paho automaticReconnect가 같은 클라이언트로 재접속하므로 새 클라이언트를 만들지 않음
                        Log.w(TAG, "❌ Uplink lost (자동 재접속 대기)", cause);
                    }
                    @Override public void messageArrived(String topic, MqttMessage message) { /* publish-only */ }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });

                up.connect(opt);
                externalConnections.put("uplink", up);
                uplinkReconnectAttempts = 0;
                Log.i(TAG, "Uplink 연결 성공");
            } catch (Exception e) {
                Log.e(TAG, "Uplink 연결 안됨", e);
                scheduleUplinkReconnect();   // 최초 연결 실패 시에만 재시도 (자동 재접속은 최초 성공 이후 동작)
            }
        });
    }
    // 외부 -> 내부 (downlink)
    private void connectExternalForDownlink(GatewayConfig cfg) {
        connectionExecutor.execute(() -> {
            try {
                closeQuietly(externalConnections.remove("downlink"));   // 기존 클라이언트 정리 (유령 연결 방지)
                String id = "AndroidGateway_Downlink";                  // 고정 ID
                Log.d(TAG, "downlink 클라이언트 연결");
                MqttClient down = new MqttClient(LOCAL_BROKER_URL, id, new MemoryPersistence());
                MqttConnectOptions opt = new MqttConnectOptions();
                opt.setAutomaticReconnect(true);
                opt.setCleanSession(true);
                opt.setKeepAliveInterval(30);
                opt.setConnectionTimeout(10);
                down.setCallback(new MqttCallbackExtended() {
                    // 최초 연결 + 자동 재접속이 완료될 때마다 호출됨.
                    // cleanSession=true라 재접속 시 구독이 사라지므로 여기서 반드시 재구독해야 downlink가 끊기지 않는다.
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            down.subscribe(config.downlinkPattern, 1);
                            Log.i(TAG, "✅ Downlink subscribed (reconnect=" + reconnect + "): " + config.downlinkPattern);
                        } catch (Exception e) {
                            Log.e(TAG, "Downlink 재구독 실패", e);
                        }
                    }
                    @Override public void connectionLost(Throwable cause) {
                        // 자동 재접속 대기. 재접속되면 connectComplete에서 재구독한다.
                        Log.w(TAG, "Downlink 끊김 (자동 재접속 대기)", cause);
                    }
                    // downlink는 수신만 하기 때문에 토픽 매칭 알고리즘이 필요함
                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        if (!topicMatches(config.compiledDownlinkPattern, topic)) {
                            Log.d(TAG, "내부 허용 토픽 패턴에 맞지 않음 " + topic);
                            return;
                        }
                        String internal = mapDownlinkToInternal(topic);
                        forwardToInternal(internal, mqttMessage.getPayload());
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                down.connect(opt);   // 구독은 connectComplete에서 수행 (최초/재접속 모두 커버)
                externalConnections.put("downlink", down);
                downlinkReconnectAttempts = 0;
                Log.i(TAG, "✅ Downlink connected: " + config.downlinkPattern);
            } catch (Exception e) {
                Log.e(TAG, "❌ Downlink connect failed", e);
                scheduleDownlinkReconnect();
            }
        });
    }

    // 재연결 관리하는 코드들
    // 최초 연결 실패 시 재시도용. 연결 유지된 뒤의 끊김은 Paho automaticReconnect가 담당한다.
    private void scheduleUplinkReconnect() {
        if (reconnectHandler == null) return;
        int shift = Math.min(uplinkReconnectAttempts, 4);   // 5s<<4=80s>MAX(60s); 1L<<n 오버플로우 방지
        long delay = Math.min(RECONNECT_DELAY * (1L << shift), MAX_RECONNECT_DELAY);
        uplinkReconnectAttempts++;
        reconnectHandler.postDelayed(() -> {
            MqttClient c = externalConnections.get("uplink");
            if (c == null || !c.isConnected()) connectExternalForUplink(config);
        }, delay);
    }

    private void scheduleDownlinkReconnect() {
        if (reconnectHandler == null) return;
        int shift = Math.min(downlinkReconnectAttempts, 4);
        long delay = Math.min(RECONNECT_DELAY * (1L << shift), MAX_RECONNECT_DELAY);
        downlinkReconnectAttempts++;
        reconnectHandler.postDelayed(() -> {
            MqttClient c = externalConnections.get("downlink");
            if (c == null || !c.isConnected()) connectExternalForDownlink(config);
        }, delay);
    }

    // 내부 브로커 바인딩
    private void connectToBrokerService() {
        if (isConnected) return;

        brokerConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "내부 브로커와 연결됨");
                messagingService = IMessagingService.Stub.asInterface(service);
                isConnected = true;
                registerGatewayListener();
                applyConfig(config);       // uplink & downlink 연결
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "내부 브로커와 끊김d");
                messagingService = null;
                isConnected = false;
                scheduleReconnect();
            }
        };

        // ipc 통신 -> 바인딩할때 필요한 intent (추후 내부 브로커 주소 바뀌면 수정 필요)
        Intent brokerIntent = new Intent();
        brokerIntent.setComponent(new ComponentName("com.example.broker", "com.example.broker.MessagingService"));
        boolean bound = bindService(brokerIntent, brokerConnection, BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "브로커와 연결하는 데 실패");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectHandler == null) return;   // 없으면 종료
        reconnectHandler.postDelayed(() -> {    // message 있으면 postDelayed로 일정시간 후에 실행되게끔 큐잉
            if (!isConnected) {
                Log.d(TAG, "재연결 시도");
                connectToBrokerService();
            }
        }, RECONNECT_DELAY);
    }

    private String generateMessageKey(String topic, byte[] payload) {
        String payloadHash = String.valueOf(java.util.Arrays.hashCode(payload));
        return topic + ":" + payloadHash;
    }

    private boolean isAlreadyProcessed(String messageKey) {
        if (processedMessages.contains(messageKey)) {
            return true;
        }

        if (processedMessages.size() >= MAX_CACHE_SIZE) {
            processedMessages.clear();
            Log.d(TAG, "캐시 청소");
        }

        processedMessages.add(messageKey);
        return false;
    }

    private void registerGatewayListener() {
        if (messagingService == null) return;
        try {
            IMessageListener gatewayListener = new IMessageListener.Stub() {
                @Override public void onMessage(Message message) throws RemoteException {
                    String topic = message.getTopic();
                    if (topic == null) return;

                    // Log.d(TAG, "🔍 받은 토픽: " + topic);

                    // downlink에서 들어온 토픽은 다시 외부로 보내지 않음 (우선 체크)
                    if (isDownlinkMappedTopic(topic)) {
                        // Log.d(TAG, "downlink로 들어온 토픽은 외부로 발행 안함(내부용): " + topic);
                        return;
                    }

                    // uplink 패턴(기본 uplink/app/+/out)에 맞는 토픽만 밖으로. 07-22 내장 브로커 작업 때 주석 처리돼 모든 내부 토픽이 나가던 것을 되돌림
                    if (config.compiledUplinkPattern != null &&
                        !topicMatches(config.compiledUplinkPattern, topic)) {
                        Log.d(TAG, "매칭안됨" + topic);
                        return;
                    }

                    Log.d(TAG, "토픽 패턴: " + topic);
                    String outTopic = mapInternalToUplink(topic);
                    Log.d(TAG, "매칭된 토픽: " + outTopic);
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

    // downlink 토픽인지 확인하는 함수 -> 필요한 경우 패턴 매칭 알고리즘 사용
    private boolean isDownlinkMappedTopic(String topic) {
//        if (config.downlinkPattern == null || topic == null) {
//            return false;
//        }
//        boolean matches = topicMatches(config.compiledDownlinkPattern, topic);
//        Log.d(TAG, "패턴 매칭 결과: " + matches);
//
//        return matches;
        return false;
    }

    private void forwardToExternal(String topic, byte[] payload) {
        String messageKey = generateMessageKey(topic, payload);
        if (isAlreadyProcessed(messageKey)) return; // 이미 처리된 메시지면 무시

        MqttClient client = externalConnections.get("uplink");
        if (client == null || !client.isConnected()) {
            Log.w(TAG, "uplink 클라이언트 유실");
            return;
        }
        try {
            if (payload == null) payload = new byte[0];
            MqttMessage out = new MqttMessage(payload);
            out.setQos(1); out.setRetained(false);
            client.publish(topic, out); // 발행
            Log.i(TAG, "외부로 발신: " + topic);
        } catch (Exception e) {
            Log.e(TAG, "에러 발생", e);
        }
    }

    private boolean topicMatches(Pattern compiledPattern, String topic) {
        if (compiledPattern == null || topic == null) return false;
        return compiledPattern.matcher(topic).matches();
    }

    // 추후 확장 -> 규칙에 안맞는 내부 토픽을 패턴에 맞게 자동으로 변환하는 기능 추가할 수 있음
    private String mapInternalToUplink(String internalTopic) {
        if (internalTopic == null) return "";
        return internalTopic;
    }

    private String mapDownlinkToInternal(String downlinkTopic) {
        if (downlinkTopic == null) return "";
        return downlinkTopic;
    }

    private void forwardToInternal(String topic, byte[] payload) {
        String messageKey = generateMessageKey(topic, payload);

        // 루프(중복) 방지: uplink, downlik가 같은 경우
        if (isAlreadyProcessed(messageKey)) {
            Log.d(TAG, "중복은 건너뜀");
            return;
        }

        if (messagingService == null) {
            Log.w(TAG, "브로커가 실행이 안됨");
            return;
        }
        try {
            if (payload == null) payload = new byte[0];
            Message msg = new Message(topic, payload);
            messagingService.publish(msg);
            Log.i(TAG, "외부에서 발신: " + topic);
        } catch (Exception e) {
            Log.e(TAG, "외부에서 발신 실패", e);
        }
    }
}
