CREATE TABLE kafka_offset (
    topic TEXT NOT NULL,
    partition SMALLINT NOT NULL,
    offset_value BIGINT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (topic, partition)
);
