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

    // Parcelable 생성자
    protected Message(Parcel in) {
        Log.d(TAG, "Reading message from parcel");

        String uuidString = in.readString();
        Log.d(TAG, "Read UUID string from parcel: " + uuidString);

        // UUID 문자열이 유효하지 않을 경우 새로 생성
        try {
            if (uuidString != null && !uuidString.equals("java.util.UUID") && !uuidString.isEmpty()) {
                this.id = UUID.fromString(uuidString);
            } else {
                this.id = UUID.randomUUID();
                Log.w(TAG, "Invalid UUID string received: '" + uuidString + "', generating new UUID: " + this.id);
            }
        } catch (IllegalArgumentException e) {
            this.id = UUID.randomUUID();
            Log.e(TAG, "Failed to parse UUID string: '" + uuidString + "', generating new UUID: " + this.id, e);
        }

        this.topic = in.readString();
        Log.d(TAG, "Read topic from parcel: " + this.topic + " (type: " + (this.topic != null ? this.topic.getClass().getSimpleName() : "null") + ")");

        this.payload = in.createByteArray();
        this.timestamp = in.readLong();

        // Bundle 역직렬화를 더 안전하게 처리
        try {
            int hasHeaders = in.readInt(); // headers 존재 플래그
            if (hasHeaders == 1) {
                this.headers = in.readBundle(getClass().getClassLoader());
                Log.d(TAG, "Read headers bundle with " + (headers != null ? headers.size() : 0) + " entries");
            } else {
                this.headers = new Bundle();
                Log.d(TAG, "Read empty headers flag, created new Bundle");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read headers bundle, creating empty Bundle", e);
            this.headers = new Bundle(); // 실패 시 빈 Bundle 생성
        }
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
        Log.d(TAG, "Writing message to parcel - topic: " + topic);

        dest.writeString(id != null ? id.toString() : null);
        dest.writeString(topic);
        dest.writeByteArray(payload);
        dest.writeLong(timestamp);

        // Bundle 직렬화를 더 안전하게 처리
        try {
            if (headers != null && !headers.isEmpty()) {
                dest.writeInt(1); // headers 존재 플래그
                dest.writeBundle(headers);
                Log.d(TAG, "Written headers bundle with " + headers.size() + " entries");
            } else {
                dest.writeInt(0); // headers 없음 플래그
                Log.d(TAG, "Written empty headers flag");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write headers bundle, writing empty flag instead", e);
            dest.writeInt(0); // 실패 시 빈 Bundle로 처리
        }
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
