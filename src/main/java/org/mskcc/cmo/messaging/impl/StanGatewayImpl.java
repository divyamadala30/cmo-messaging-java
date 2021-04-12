package org.mskcc.cmo.messaging.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection.Status;
import io.nats.streaming.Message;
import io.nats.streaming.MessageHandler;
import io.nats.streaming.NatsStreaming;
import io.nats.streaming.Options;
import io.nats.streaming.StreamingConnection;
import io.nats.streaming.StreamingConnectionFactory;
import io.nats.streaming.Subscription;
import io.nats.streaming.SubscriptionOptions;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.cmo.common.FileUtil;
import org.mskcc.cmo.messaging.Gateway;
import org.mskcc.cmo.messaging.MessageConsumer;
import org.mskcc.cmo.messaging.utils.SSLUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StanGatewayImpl implements Gateway {

    // TDB set to true after all clients are updated?
    @Value("${nats.tls_channel:false}")
    private boolean tlsChannel;

    @Value("${nats.clusterid}")
    private String clusterId;

    @Value("${nats.clientid}")
    private String clientId;

    @Value("${nats.url}")
    private String natsURL;

    @Value("${metadb.publishing_failures_filepath}")
    private String metadbPubFailuresFilepath;

    private FileUtil fileUtil;

    private File pubFailuresFile;

    @Autowired
    private void initPubFailuresFile() throws IOException {
        this.pubFailuresFile = fileUtil.getOrCreateFileWithHeader(
                metadbPubFailuresFilepath, PUB_FAILURES_FILE_HEADER);
    }

    @Autowired
    SSLUtils sslUtils;

    private static final String PUB_FAILURES_FILE_HEADER = "DATE\tTOPIC\tMESSAGE\n";
    private StreamingConnection stanConnection;
    private StreamingConnectionFactory connFact;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Subscription> subscribers = new HashMap<String, Subscription>();
    private volatile boolean shutdownInitiated;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final CountDownLatch publishingShutdownLatch = new CountDownLatch(1);
    private final BlockingQueue<PublishingQueueTask> publishingQueue =
        new LinkedBlockingQueue<PublishingQueueTask>();
    private final Log LOG = LogFactory.getLog(StanGatewayImpl.class);

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
            this.sc = NatsStreaming.connect(clusterId, clientId + "-publisher", opts);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    PublishingQueueTask task = publishingQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        String msg = mapper.writeValueAsString(task.message);
                        try {
                            sc.publish(task.topic, msg.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException | InterruptedException | TimeoutException e) {
                            try {
                                fileUtil.writeToFile(pubFailuresFile,
                                        generatePublishFailureRecord(task.topic, msg));
                            } catch (IOException ex) {
                                LOG.error("Error during attempt to log publishing failure to file: "
                                        + metadbPubFailuresFilepath, e);
                            }
                            if (e instanceof InterruptedException) {
                                interrupted = true;
                            } else {
                                LOG.error("Error during attempt to publish on topic: " + task.topic, e);
                            }
                        }
                    }
                    if ((interrupted || shutdownInitiated) && publishingQueue.isEmpty()) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (JsonProcessingException e) {
                    LOG.error("Error parsing JSON from message", e);
                }
            }
            try {
                sc.close();
                publishingShutdownLatch.countDown();
            } catch (Exception e) {
                LOG.error("Error closing streaming connection: %s\n" + e.getMessage());
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
            LOG.error("Shutdown initiated, not accepting publish request: \n" + message);
            throw new IllegalStateException("Shutdown initiated, not accepting anymore publish requests");
        }
    }

    @Override
    public void publish(String stream, String subject, Object message) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void connect() throws Exception {
        connect(clusterId, clientId, natsURL);
    }

    @Override
    public void connect(String natsUrl) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void connect(String clusterId, String clientId, String natsUrl) throws Exception {
        // setup nats connection w/SSL context if required
        io.nats.client.Options.Builder builder = new io.nats.client.Options.Builder()
            .server(natsURL);
        if (tlsChannel) {
            builder.sslContext(sslUtils.createSSLContext());
        }
        io.nats.client.Connection connection = io.nats.client.Nats.connect(builder.build());
        // pass nats connection to stan options
        Options opts = new Options.Builder()
            .clientId(clientId)
            .clusterId(clusterId)
            .natsConn(connection)
            .build();
        connFact = new StreamingConnectionFactory(opts);
        stanConnection = connFact.createConnection();
        exec.execute(new NATSPublisher());
    }

    @Override
    public boolean isConnected() {
        if (stanConnection == null) {
            return Boolean.FALSE;
        }
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
                        message = mapper.readValue(json, messageClass);
                    } catch (Exception e) {
                        LOG.error("Error deserializing NATS message: \n" + msg);
                        LOG.error("Exception: \n" + e.getMessage());
                    }
                    if (message != null) {
                        consumer.onMessage(message);
                    }
                }
            }, new SubscriptionOptions.Builder().durableName(topic + "-" + clientId).build());
            subscribers.put(topic, sub);
        }
    }

    @Override
    public void subscribe(String stream, String subject, Class messageClass, MessageConsumer messageConsumer)
            throws Exception {
        throw new UnsupportedOperationException();
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

    /**
     * Generates record to write to publishing failure file.
     * @param topic
     * @param message
     * @return String
     */
    private String generatePublishFailureRecord(String topic, String message) {
        String currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder builder = new StringBuilder();
        builder.append(currentDate)
                .append("\t")
                .append(topic)
                .append("\t")
                .append(message)
                .append("\n");
        return builder.toString();
    }
}
