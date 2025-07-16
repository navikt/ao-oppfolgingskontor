ALTER TABLE arenakontor
    DROP COLUMN IF EXISTS kafka_offset;
ALTER TABLE arenakontor
    DROP COLUMN IF EXISTS kafka_partition;
