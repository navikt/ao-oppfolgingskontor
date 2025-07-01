ALTER TABLE failed_messages
    ADD topic TEXT NOT NULL;

DROP INDEX idx_failed_messages_key_timestamp;
CREATE INDEX idx_failed_messages_key ON failed_messages (message_key_text);
CREATE INDEX idx_failed_messages_topic_timestamp ON failed_messages (topic, queue_timestamp);
