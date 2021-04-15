package org.mskcc.cmo.messaging.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamOptions;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.PublishAck;
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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.cmo.common.FileUtil;
import org.mskcc.cmo.messaging.Gateway;
import org.mskcc.cmo.messaging.MessageConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JSGatewayImpl implements Gateway {
    private final Log LOG = LogFactory.getLog(JSGatewayImpl.class);
    private final CountDownLatch publishingShutdownLatch = new CountDownLatch(1);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JetStreamSubscription> subscribers = new HashMap<>();
    private volatile BlockingQueue<PublishingQueueTask> publishingQueue = new LinkedBlockingQueue<>();
    private volatile ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile Boolean shutdownInitiated = Boolean.FALSE;

    // connection variables
    @Value("${nats.url}")
    public String natsUrl;

    private Connection natsConnection;
    private JetStream jsConnection;

    // publishing logger file variables
    @Value("${metadb.publishing_failures_filepath}")
    private String metadbPubFailuresFilepath;

    private final String PUB_FAILURES_FILE_HEADER = "DATE\tTOPIC\tMESSAGE\n";
    private FileUtil fileUtil;
    private File publishingLoggerFile;

    @Override
    public void connect() throws Exception {
        connect(natsUrl);
    }

    @Override
    public void connect(String natsUrl) throws Exception {
        this.natsConnection = Nats.connect(natsUrl);
        this.jsConnection = natsConnection.jetStream();
        publishingLoggerFile = fileUtil.getOrCreateFileWithHeader(
                metadbPubFailuresFilepath, PUB_FAILURES_FILE_HEADER);
        exec.execute(new NATSPublisher(natsConnection.getOptions()));
    }

    @Override
    public  boolean isConnected() {
        if (natsConnection == null) {
            return Boolean.FALSE;
        }
        return (natsConnection != null && natsConnection.getStatus() != null
                && (natsConnection.getStatus().equals(Connection.Status.CONNECTED)));
    }

    @Override
    public void publish(String stream, String subject, Object message) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Gateway connection has not been established.");
        }
        if (!shutdownInitiated) {
            publishingQueue.put(new PublishingQueueTask(subject, message));
        } else {
            LOG.error("Shutdown initiated, not accepting publish request: \n" + message);
            throw new IllegalStateException("Shutdown initiated, not accepting anymore publish requests");
        }
    }

    @Override
    public void subscribe(String stream, String subject, Class messageClass,
            MessageConsumer messageConsumer) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Gateway connection has not been established.");
        }
        if (!subscribers.containsKey(subject)) {
            Dispatcher dispatcher = natsConnection.createDispatcher();
            //Should we add PushSubscribeOptions to add durable name?
            JetStreamSubscription sub = jsConnection.subscribe(subject, dispatcher,
                msg -> onMessage(msg, messageClass, messageConsumer), false);
            subscribers.put(subject, sub);
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
        natsConnection.close();
    }

    /**
     * Configure basic message handler logic.
     * @param msg
     * @param messageClass
     * @param messageConsumer
     */
    public void onMessage(Message msg, Class messageClass, MessageConsumer messageConsumer) {
        String payload = new String(msg.getData(), StandardCharsets.UTF_8);
        Object message = null;

        try {
            message = mapper.readValue(payload, messageClass);
        } catch (JsonProcessingException ex) {
            LOG.error("Error deserializing NATS message: " + payload, ex);
        }
        if (message != null) {
            messageConsumer.onMessage(message);
        }
    }

    /**
     * Writes to publishing logger file.
     * @param subject
     * @param message
     */
    public void writeToPublishingLoggerFile(String subject, String message) {
        String currentDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        StringBuilder builder = new StringBuilder();
        builder.append(currentDate)
                .append("\t")
                .append(subject)
                .append("\t")
                .append(message)
                .append("\n");

        try {
            fileUtil.writeToFile(publishingLoggerFile,
                    builder.toString());
        } catch (IOException ex) {
            LOG.error("Error during attempt to log publishing task to logger file", ex);
        }
    }

    private class NATSPublisher implements Runnable {
        Connection natsConn;
        JetStream jsConn;
        boolean interrupted;

        /**
         * NATSPublisher constructor.
         * @param options
         * @throws IOException
         * @throws InterruptedException
         */
        public NATSPublisher(Options options) throws IOException, InterruptedException {
            this.natsConn = Nats.connect(options);
            this.jsConn = natsConn.jetStream(JetStreamOptions.DEFAULT_JS_OPTIONS);
            this.interrupted = Boolean.FALSE;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    PublishingQueueTask task = publishingQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        String msg = mapper.writeValueAsString(task.payload);
                        try {
                            PublishAck ack = jsConn.publish(task.subject, msg.getBytes());
                            if (ack.getError() != null) {
                                writeToPublishingLoggerFile(task.subject, msg);
                            }
                        } catch (Exception e) {
                            writeToPublishingLoggerFile(task.subject, msg);
                            if (e instanceof InterruptedException) {
                                interrupted = Boolean.TRUE;
                            } else {
                                LOG.error("Error during attempt to publish on topic: " + task.subject, e);
                            }
                        }
                        if ((interrupted || shutdownInitiated) && publishingQueue.isEmpty()) {
                            break;
                        }
                    }
                } catch (InterruptedException ex) {
                    interrupted = Boolean.TRUE;
                } catch (JsonProcessingException ex) {
                    LOG.error("Error parsing JSON from message", ex);
                }
            }
            try {
                // close connection
                natsConn.close();
            } catch (InterruptedException ex) {
                LOG.error("Error during attempt to close NATS connection", ex);
            }
            publishingShutdownLatch.countDown();
        }
    }

    private class PublishingQueueTask {
        String subject;
        Object payload;

        public PublishingQueueTask(String subject, Object payload) {
            this.subject = subject;
            this.payload = payload;
        }
    }
}
