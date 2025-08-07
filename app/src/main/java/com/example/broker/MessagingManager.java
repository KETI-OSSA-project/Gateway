package com.example.broker;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 통합된 메시징 매니저 클래스
 * 서비스 바인딩과 메시지 전달 기능을 모두 담당
 */
public class MessagingManager {
    private static final String TAG = "MessagingManager";
    private static MessagingManager instance;

    private Context context;
    private List<IMessageListener> gatewayListeners;
    private boolean isInitialized = false;

    private MessagingManager() {
        gatewayListeners = new ArrayList<>();
    }

    public static synchronized MessagingManager getInstance() {
        if (instance == null) {
            instance = new MessagingManager();
        }
        return instance;
    }

    /**
     * 매니저 초기화
     */
    public void initialize(Context context) {
        this.context = context.getApplicationContext();
        this.isInitialized = true;
        Log.d(TAG, "MessagingManager initialized");
    }

    /**
     * 게이트웨이 리스너 등록
     */
    public void setGatewayListener(IMessageListener listener, MessagingServiceConnectionListener connectionCallback) {
        Log.d(TAG, "setGatewayListener 호출됨");

        synchronized (gatewayListeners) {
            if (!gatewayListeners.contains(listener)) {
                gatewayListeners.add(listener);
                Log.d(TAG, "게이트웨이 리스너 등록됨. 총 " + gatewayListeners.size() + "개");
            }
        }

        // 즉시 연결 완료 콜백 호출 (서비스 바인딩 불필요)
        if (connectionCallback != null) {
            connectionCallback.onConnected();
        }
    }

    /**
     * 브로커에서 메시지를 받아서 등록된 게이트웨이 리스너들에게 전달
     * 이제 MessagingService 없이 직접 처리
     */
    public void forwardMessageToGateway(Message message) {
        Log.d(TAG, "브로커에서 메시지 수신: " + message.getTopic());

        synchronized (gatewayListeners) {
            if (gatewayListeners.isEmpty()) {
                Log.w(TAG, "등록된 게이트웨이 리스너가 없음");
                return;
            }

            // 모든 등록된 게이트웨이 리스너에게 메시지 전달
            List<IMessageListener> listenersToRemove = new ArrayList<>();
            for (IMessageListener listener : gatewayListeners) {
                try {
                    listener.onMessage(message);
                    Log.d(TAG, "게이트웨이로 메시지 전달 성공: " + message.getTopic());
                } catch (RemoteException e) {
                    Log.e(TAG, "게이트웨이 메시지 전달 실패 - 리스너 제거", e);
                    listenersToRemove.add(listener);
                }
            }

            // 실패한 리스너들 제거
            gatewayListeners.removeAll(listenersToRemove);
        }
    }

    /**
     * 게이트웨이 리스너 제거
     */
    public void removeGatewayListener(IMessageListener listener) {
        synchronized (gatewayListeners) {
            gatewayListeners.remove(listener);
            Log.d(TAG, "게이트웨이 리스너 제거됨. 남은 개수: " + gatewayListeners.size());
        }
    }

    /**
     * 모든 리스너 정리
     */
    public void disconnect() {
        synchronized (gatewayListeners) {
            gatewayListeners.clear();
            Log.d(TAG, "모든 게이트웨이 리스너 정리됨");
        }
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 연결 상태 콜백 인터페이스 (호환성을 위해 유지)
     */
    public interface MessagingServiceConnectionListener {
        void onConnected();
        void onDisconnected();
    }

    /**
     * 기존 API와 호환성을 위한 정적 메서드
     */
    public static MessagingManager getConnectedInstance(Context context, MessagingServiceConnectionListener listener) {
        MessagingManager manager = getInstance();
        manager.initialize(context);

        // 즉시 연결 완료 콜백 (서비스 바인딩이 없으므로)
        if (listener != null) {
            listener.onConnected();
        }

        return manager;
    }
}
