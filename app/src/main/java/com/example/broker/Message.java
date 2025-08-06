package com.example.broker;

import android.os.Parcel;
import android.os.Parcelable;

public class Message implements Parcelable {
    // Message header key names - Broker와 동일한 상수 추가
    public static final String TOPIC = "topic";
    public static final String PUBLISHER_ID = "publisherId";
    public static final String DESTINATION_IDS = "destinationIds";

    private String topic;
    private byte[] payload;
    private String timestamp;

    public Message() {}

    public Message(String topic, byte[] payload, String timestamp) {
        this.topic = topic;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public Message(String topic, String payload) {
        this.topic = topic;
        this.payload = payload.getBytes();
        this.timestamp = String.valueOf(System.currentTimeMillis());
    }

    protected Message(Parcel in) {
        topic = in.readString();
        payload = in.createByteArray();
        timestamp = in.readString();
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

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(topic);
        dest.writeByteArray(payload);
        dest.writeString(timestamp);
    }
}
