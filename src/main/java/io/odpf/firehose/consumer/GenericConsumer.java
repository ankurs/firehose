package io.odpf.firehose.consumer;

import com.newrelic.api.agent.Trace;
import io.odpf.firehose.config.KafkaConsumerConfig;
import io.odpf.firehose.consumer.committer.OffsetCommitter;
import io.odpf.firehose.filter.Filter;
import io.odpf.firehose.filter.FilterException;
import io.odpf.firehose.metrics.Instrumentation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class responsible for consuming the messages in kafka.
 * It is capable of applying filters supplied while instantiating this consumer {@see io.odpf.firehose.factory.GenericKafkaFactory},
 * {@see Filter}.
 */
public class GenericConsumer {

    private final Consumer kafkaConsumer;
    private final KafkaConsumerConfig consumerConfig;
    private final Filter filter;
    private final OffsetCommitter offsetCommitter;
    private final Instrumentation instrumentation;
    private final Map<TopicPartition, OffsetAndMetadata> committedOffsets = new HashMap<>();
    private ConsumerRecords<byte[], byte[]> records;

    /**
     * A Constructor.
     *
     * @param kafkaConsumer   {@see KafkaConsumer}
     * @param config          Consumer configuration.
     * @param filter          a Filter implementation to filter the messages. {@see Filter}, {@see io.odpf.firehose.filter.EsbMessageFilter}
     * @param offsetCommitter {@see Offsets}
     * @param instrumentation Contain logging and metrics collection
     */
    public GenericConsumer(Consumer kafkaConsumer, KafkaConsumerConfig config, Filter filter,
                           OffsetCommitter offsetCommitter, Instrumentation instrumentation) {
        this.kafkaConsumer = kafkaConsumer;
        this.consumerConfig = config;
        this.filter = filter;
        this.offsetCommitter = offsetCommitter;
        this.instrumentation = instrumentation;
    }

    /**
     * method to read next batch of messages from kafka.
     *
     * @return list of EsbMessage {@see EsbMessage}
     * @throws FilterException in case of error when applying the filter condition.
     */
    public List<Message> readMessages() throws FilterException {
        this.records = kafkaConsumer.poll(Duration.ofMillis(consumerConfig.getSourceKafkaPollTimeoutMs()));
        instrumentation.logInfo("Pulled {} messages", records.count());
        instrumentation.capturePulledMessageHistogram(records.count());
        List<Message> messages = new ArrayList<>();

        for (ConsumerRecord<byte[], byte[]> record : records) {
            messages.add(new Message(record.key(), record.value(), record.topic(), record.partition(), record.offset(), record.headers(), record.timestamp(), System.currentTimeMillis()));
            instrumentation.logDebug("Pulled record: {}", record);
        }
        return filter(messages);
    }

    private List<Message> filter(List<Message> messages) throws FilterException {
        List<Message> filteredMessage = filter.filter(messages);
        Integer filteredMessageCount = messages.size() - filteredMessage.size();
        if (filteredMessageCount > 0) {
            instrumentation.captureFilteredMessageCount(filteredMessageCount, consumerConfig.getFilterJexlExpression());
        }
        return filteredMessage;
    }

    @Trace(dispatcher = true)
    public void commit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsetCommitter.commit(offsets);
    }

    @Trace(dispatcher = true)
    public void commit() {
        offsetCommitter.commit(records);
    }

    public void close() {
        try {
            instrumentation.logInfo("Consumer is closing");
            this.kafkaConsumer.close();
        } catch (Exception e) {
            instrumentation.captureNonFatalError(e, "Exception while closing consumer");
        }
    }

    public ConsumerRecords<byte[], byte[]> getRecords() {
        return records;
    }

    public Filter getFilter() {
        return filter;
    }
}
