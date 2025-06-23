ALTER TABLE failed_messages
    ADD kafka_partition INT,
    ADD kafka_offset INT,
    ADD topic TEXT NOT NULL DEFAULT 'default_topic';

DROP INDEX idx_failed_messages_key_timestamp;
CREATE INDEX idx_failed_messages_key ON failed_messages (message_key_text);
CREATE INDEX idx_failed_messages_topic_timestamp ON failed_messages (topic, kafka_partition, queue_timestamp);
