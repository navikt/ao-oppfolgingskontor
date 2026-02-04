create index idx_failed_message_queue_timestamp
    on failed_messages (queue_timestamp);
create index idx_failed_message_topic
    on failed_messages (topic);