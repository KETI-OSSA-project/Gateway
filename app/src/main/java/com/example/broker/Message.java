package com.example.broker;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.UUID;

/**
 * 브로커와 완전히 동일한 Message 클래스
 */
public class Message implements Parcelable {
    private static final String TAG = "Message";

    // Message header key names - Broker와 동일한 상수 추가
    public static final String TOPIC = "topic";
    public static final String PUBLISHER_ID = "publisherId";
    public static final String DESTINATION_IDS = "destinationIds";

    private UUID id;
    private String topic;
    private byte[] payload;
    private long timestamp;
    private Bundle headers;

    // 브로커 전용 필드들 (읽기 위해서만 필요)
    private String responseTopic;
    private byte[] correlationData;

    // 기본 생성자
    public Message() {
        this.id = UUID.randomUUID();
        this.timestamp = System.currentTimeMillis();
        this.headers = new Bundle();
    }

    // 생성자
    public Message(String topic, byte[] payload) {
        this();
        this.topic = topic;
        this.payload = payload;
        Log.d(TAG, "Creating message with topic: " + topic + " (type: " + (topic != null ? topic.getClass().getSimpleName() : "null") + ")");
    }

    // Parcelable 생성자 - 브로커 포맷에 맞춤
    protected Message(Parcel in) {
        Log.d(TAG, "Reading message from parcel (BROKER FORMAT)");

        // 1) UUID는 Serializable로 써졌음
        try {
            Object uuidObj = in.readSerializable();
            if (uuidObj instanceof UUID) {
                this.id = (UUID) uuidObj;
                Log.d(TAG, "Read UUID from parcel: " + this.id);
            } else {
                this.id = UUID.randomUUID();
                Log.w(TAG, "Invalid UUID object, generated new: " + this.id);
            }
        } catch (Exception e) {
            this.id = UUID.randomUUID();
            Log.e(TAG, "Failed to read UUID, generated new: " + this.id, e);
        }

        // 2) Bundle (topic 등)
        try {
            this.headers = in.readBundle(getClass().getClassLoader());
            if (this.headers == null) {
                this.headers = new Bundle();
            }

            // 브로커는 topic을 Bundle에 넣음
            this.topic = headers.getString(TOPIC);
            Log.d(TAG, "Read topic from bundle: " + this.topic);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read headers bundle", e);
            this.headers = new Bundle();
            this.topic = null;
        }

        // 3) payload
        this.payload = in.createByteArray();
        Log.d(TAG, "Read payload: " + (payload != null ? payload.length : 0) + " bytes");

        // 4) responseTopic (브로커만 사용, 읽고 버림)
        this.responseTopic = in.readString();

        // 5) correlationData (브로커만 사용, 읽고 버림)
        this.correlationData = in.createByteArray();

        // 6) timestamp
        this.timestamp = in.readLong();
        Log.d(TAG, "Read timestamp: " + this.timestamp);
    }

    public static final Creator<Message> CREATOR = new Creator<Message>() {
        @Override
        public Message createFromParcel(Parcel in) {
            return new Message(in);
        }

        @Override
        public Message[] newArray(int size) {
            return new Message[size];
        }
    };

    // Getters
    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public byte[] getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Bundle getHeaders() {
        return headers;
    }

    // Setters
    public void setTopic(String topic) {
        this.topic = topic;
        Log.d(TAG, "Setting topic: " + topic + " (type: " + (topic != null ? topic.getClass().getSimpleName() : "null") + ")");
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public void setHeaders(Bundle headers) {
        this.headers = headers;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        Log.d(TAG, "Writing message to parcel (BROKER FORMAT) - topic: " + topic);

        // topic을 브로커가 Bundle에서 꺼내므로 headers에 넣어둠
        if (headers == null) headers = new Bundle();
        if (topic != null) headers.putString(TOPIC, topic);

        // 브로커 포맷에 맞춰 쓰기
        dest.writeSerializable(id);              // 1) UUID 객체
        dest.writeBundle(headers);               // 2) Bundle (topic 포함)
        dest.writeByteArray(payload);            // 3) payload
        dest.writeString(responseTopic);         // 4) responseTopic (null이어도 OK)
        dest.writeByteArray(correlationData);    // 5) correlationData (null이어도 OK)
        dest.writeLong(timestamp);               // 6) timestamp

        Log.d(TAG, "Written message to parcel successfully");
    }

    @Override
    public String toString() {
        return "Message{" +
                "id=" + id +
                ", topic='" + topic + '\'' +
                ", payloadSize=" + (payload != null ? payload.length : 0) + " bytes" +
                ", timestamp=" + timestamp +
                '}';
    }
}
