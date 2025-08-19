CREATE TABLE dead_letter_queue
(
    id                     SERIAL PRIMARY KEY,
    message_key_text       varchar(255),
    message_key_bytes      bytea,
    message_value          bytea,
    queue_timestamp        timestamp with time zone,
    last_attempt_timestamp timestamp with time zone,
    retry_count            integer,
    failure_reason         text,
    topic                  text,
    human_readable_value   text
);

CREATE INDEX idx_dlq_message_key_text       ON dead_letter_queue (message_key_text);
CREATE INDEX idx_dlq_topic                  ON dead_letter_queue (topic);
CREATE INDEX idx_dlq_queue_timestamp        ON dead_letter_queue (queue_timestamp);