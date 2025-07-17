update arenakontor set kafka_offset = null, kafka_partition = null where kafka_offset is not null or kafka_partition is not null;
