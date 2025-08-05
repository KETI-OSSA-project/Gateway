package com.example.broker;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;

/**
 * Container class encapsulating messages for enabling communication between different clients using
 * the message broker service.
 */
public class Message implements Parcelable {

    // Message header key names (통합된 상수들)
    public static final String TOPIC = "topic";
    public static final String PUBLISHER_ID = "publisherId";
    public static final String DESTINATION_IDS = "destinationIds";

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

    private final UUID messageId;
    private final Bundle bundle;
    private final byte[] payload;
    private final String responseTopic;
    private final byte[] correlationData;
    private final long timeStamp;

    /**
     * @deprecated Use Message.Builder instead
     */
    @Deprecated
    public Message(String topic, byte[] payload, HashSet<String> destinationIds) {
        this.messageId = UUID.randomUUID();
        this.bundle = new Bundle();
        this.payload = payload;
        this.responseTopic = null;
        this.correlationData = null;
        this.timeStamp = System.currentTimeMillis(); // 자바 버전으로 인해 System.currentTimeMillis() 사용

        if (topic != null) {
            bundle.putString(TOPIC, topic);
        }
        if (destinationIds != null) {
            bundle.putStringArrayList(DESTINATION_IDS, new ArrayList<>(destinationIds));
        }
    }

    /**
     * @deprecated Use Message.Builder instead
     */
    @Deprecated
    public Message(String topic, byte[] payload) {
        this(topic, payload, null);
    }

    private Message(Builder builder) {
        this.messageId = UUID.randomUUID();
        this.bundle = new Bundle();
        this.payload = builder.payload;
        this.responseTopic = builder.responseTopic;
        this.correlationData = builder.correlationData;
        this.timeStamp = builder.timeStamp; // Builder에서 설정된 timeStamp 사용

        if (builder.topic != null) {
            bundle.putString(TOPIC, builder.topic);
        }
        if (builder.destinationIds != null) {
            bundle.putStringArrayList(DESTINATION_IDS, new ArrayList<>(builder.destinationIds));
        }
    }

    private Message(Parcel in) {
        messageId = (UUID) in.readSerializable();
        bundle = in.readBundle(getClass().getClassLoader());
        payload = in.createByteArray();
        responseTopic = in.readString();
        correlationData = in.createByteArray();
        timeStamp = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeSerializable(messageId);
        dest.writeBundle(bundle);
        dest.writeByteArray(payload);
        dest.writeString(responseTopic);
        dest.writeByteArray(correlationData);
        dest.writeLong(timeStamp);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Get the topic the message belongs to.
     */
    public String getTopic() {
        return bundle.getString(TOPIC);
    }

    /**
     * Get the payload encapsulated in the message.
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Get the identifier of the message.
     */
    public UUID getMessageId() {
        return messageId;
    }

    /**
     * Get the response topic of the message.
     */
    public String getResponseTopic() {
        return responseTopic;
    }

    /**
     * Get the correlation data encapsulated in the message.
     */
    public byte[] getCorrelationData() {
        return correlationData;
    }

    /**
     * Get the set of destination identifiers the message is directed to.
     */
    public Set<String> getDestinationIds() {
        ArrayList<String> destinationIds = bundle.getStringArrayList(DESTINATION_IDS);
        if (destinationIds == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(destinationIds);
    }

    /**
     * Get the publisher id of the message.
     */
    public String getPublisherId() {
        return bundle.getString(PUBLISHER_ID);
    }

    /**
     * Get the timeStamp of the message.
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    public static class Builder {
        private String topic;
        private byte[] payload;
        private String responseTopic;
        private byte[] correlationData;
        private HashSet<String> destinationIds;
        private long timeStamp;

        /**
         * Constructor.
         */
        public Builder(String topic) {
            this.topic = topic;
            this.timeStamp = System.currentTimeMillis(); // 기본값으로 현재 시간 설정
        }

        /**
         * Build Message object from the builder object.
         */
        public Message build() {
            return new Message(this);
        }

        /**
         * Optional method for setting the correlation data of the message.
         */
        public Builder setCorrelationData(byte[] correlationData) {
            this.correlationData = correlationData;
            return this;
        }

        /**
         * Optional method to set the destinations of the message.
         */
        public Builder setDestinationIds(HashSet<String> destinationIds) {
            this.destinationIds = destinationIds;
            return this;
        }

        /**
         * @deprecated Use setDestinationIds(HashSet) instead
         */
        @Deprecated
        public Builder setDestionationIds(HashSet<String> destinationIds) {
            return setDestinationIds(destinationIds);
        }

        /**
         * Optional method for setting the payload of the message.
         */
        public Builder setPayload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Optional method for setting the response topic of the message.
         */
        public Builder setResponseTopic(String responseTopic) {
            this.responseTopic = responseTopic;
            return this;
        }

        /**
         * Optional method to set time stamp of the message.
         */
        public Builder setTimeStamp(long timeStamp) {
            this.timeStamp = timeStamp; // 자바 버전 문제로 인해 instant -> long 변경
            return this;
        }

        // Backward compatibility - keep existing setTopic method
        public Builder setTopic(@NonNull String topic) {
            this.topic = topic;
            return this;
        }
    }
}
