CREATE TABLE failed_messages (
    id BIGSERIAL PRIMARY KEY,                    -- Unik ID for hver rad
    message_key_text VARCHAR(255) NOT NULL,           -- For indeksering og feilsøking
    message_key_bytes BYTEA,                   -- Den serialiserte nøkkelen fra Kafka-meldingen
    message_value BYTEA NOT NULL,                -- Selve meldingen (payload), BYTEA er fleksibelt,
                                            -- Alternativt: JSONB hvis du vet det alltid er JSON
    queue_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- Tidspunktet meldingen ble lagt i kø, for FIFO-rekkefølge
    last_attempt_timestamp TIMESTAMPTZ,          -- Når vi sist prøvde å reprosessere
    retry_count INT NOT NULL DEFAULT 0,          -- Antall forsøk på reprosessering
    failure_reason TEXT                          -- En kort beskrivelse av siste feil
);

-- VIKTIG: indeks for raskt oppslag på nøkkel og for å hente i riktig rekkefølge.
CREATE INDEX idx_failed_messages_key_timestamp ON failed_messages (message_key_text, queue_timestamp);