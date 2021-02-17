package org.mskcc.cmo.messaging.impl;

import com.google.gson.Gson;
import io.nats.client.Connection.Status;
import io.nats.streaming.Message;
import io.nats.streaming.MessageHandler;
import io.nats.streaming.NatsStreaming;
import io.nats.streaming.Options;
import io.nats.streaming.StreamingConnection;
import io.nats.streaming.StreamingConnectionFactory;
import io.nats.streaming.Subscription;
import io.nats.streaming.SubscriptionOptions;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;
import org.mskcc.cmo.messaging.Gateway;
import org.mskcc.cmo.messaging.MessageConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NATSGatewayImpl implements Gateway {

    @Value("${nats.clusterid}")
    private String clusterID;

    @Value("${nats.clientid}")
    private String clientID;

    @Value("${nats.url}")
    private String natsURL;

    private StreamingConnection stanConnection;
    private StreamingConnectionFactory connFact;
    private final Gson gson = new Gson();
    private final Map<String, Subscription> subscribers = new HashMap<String, Subscription>();
    private volatile boolean shutdownInitiated;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final CountDownLatch publishingShutdownLatch = new CountDownLatch(1);
    private final BlockingQueue<PublishingQueueTask> publishingQueue =
        new LinkedBlockingQueue<PublishingQueueTask>();
    private Logger logger = Logger.getLogger(NATSGatewayImpl.class);

    private class PublishingQueueTask {
        String topic;
        Object message;

        PublishingQueueTask(String topic, Object message) {
            this.topic = topic;
            this.message = message;
        }
    }

    private class NATSPublisher implements Runnable {

        StreamingConnection sc;
        boolean interrupted = false;

        NATSPublisher() throws Exception {
            Options opts = new Options.Builder()
                    .errorListener(new StreamingErrorListener())
                    .natsConn(stanConnection.getNatsConnection())
                    .build();
            this.sc = NatsStreaming.connect(clusterID, clientID + "-publisher", opts);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    PublishingQueueTask task = publishingQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        String msg = gson.toJson(task.message);
                        try {
                            sc.publish(task.topic, msg.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            // TBD requeue?
                            logger.error("Error publishing to topic: "
                                    + task.topic + "\n Message: " + msg);
                        }
                    }
                    if (interrupted && publishingQueue.isEmpty()) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            try {
                sc.close();
                publishingShutdownLatch.countDown();
            } catch (Exception e) {
                System.err.printf("Error closing streaming connection: %s\n", e.getMessage());
            }
        }
    }

    @Override
    public void publish(String topic, Object message) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Gateway connection has not been established");
        }
        if (!shutdownInitiated) {
            PublishingQueueTask task = new PublishingQueueTask(topic, message);
            publishingQueue.put(task);
        } else {
            System.err.printf("Shutdown initiated, not accepting publish request: %s\n", message);
            throw new IllegalStateException("Shutdown initiated, not accepting anymore publish requests");
        }
    }

    @Override
    public void connect() throws Exception {
        Options opts = new Options.Builder()
                .clientId(clientID)
                .clusterId(clusterID)
                .natsUrl(natsURL)
                .build();
        connFact = new StreamingConnectionFactory(opts);
        stanConnection = connFact.createConnection();
        exec.execute(new NATSPublisher());
    }

    @Override
    public boolean isConnected() {
        return (stanConnection != null && stanConnection.getNatsConnection() != null
                && (stanConnection.getNatsConnection().getStatus().CONNECTED.equals(Status.CONNECTED)));
    }

    @Override
    public void subscribe(String topic, Class messageClass, MessageConsumer consumer) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Gateway connection has not been established");
        }
        // we may want to change this check -
        // to allow for more than one consumer per topic
        if (!subscribers.containsKey(topic)) {
            Subscription sub = stanConnection.subscribe(topic, new MessageHandler() {
                @Override
                public void onMessage(Message msg) {
                    Object message = null;
                    try {
                        String json = new String(msg.getData(), StandardCharsets.UTF_8);
                        message = gson.fromJson(json, messageClass);
                    } catch (Exception e) {
                        System.err.printf("Error deserializing NATS message: %s\n", msg);
                        System.err.printf("Exception: %s\n", e.getMessage());
                    }
                    if (message != null) {
                        consumer.onMessage(message);
                    }
                }
            }, new SubscriptionOptions.Builder().durableName(topic + "-" + clientID).build());
            subscribers.put(topic, sub);
        }
    }

    @Override
    public void shutdown() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Gateway connection has not been established");
        }
        exec.shutdownNow();
        shutdownInitiated = true;
        publishingShutdownLatch.await();
        stanConnection.close();
    }
}
