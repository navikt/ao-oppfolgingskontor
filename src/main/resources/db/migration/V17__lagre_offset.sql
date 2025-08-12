CREATE TABLE kafka_offset (
    topic TEXT NOT NULL,
    partition SMALLINT NOT NULL,
    offset BIGINT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT kafka_offset_topic_partition_unique UNIQUE (topic, partition)
);
