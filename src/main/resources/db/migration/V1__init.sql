CREATE TABLE arbeidsoppfolgingskontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontor_id VARCHAR(4),
    endret_av VARCHAR(255),
    endret_av_type VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE arenakontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontor_id VARCHAR(4),
    endret_av VARCHAR(255),
    endret_av_type VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    sist_endret_dato_arena TIMESTAMPTZ,
    kafka_offset BIGINT,
    kafka_partition INT
);

CREATE TABLE geografisktilknytningkontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontor_id VARCHAR(4),
    endret_av VARCHAR(255),
    endret_av_type VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE aktorid (
    fnr VARCHAR(11) PRIMARY KEY,
    aktor_id VARCHAR(13)
);

CREATE TABLE kontorhistorikk (
    id SERIAL PRIMARY KEY,
    fnr VARCHAR(11),
    kontor_id VARCHAR(4),
    endret_av VARCHAR(255),
    endret_av_type VARCHAR(255),
    kontorendringstype VARCHAR(255),
    -- "Triggere" for at bruker for tildelt et kontor
    -- Automatisk fordeling ved Arbeidssøkerregistrering
    -- Manuelt flyttet av veileder
    -- Endret i Arena
    -- Endret skjermingsstatus
    -- Endret addressebeskyttelse
    created_at TIMESTAMPTZ DEFAULT NOW()
);