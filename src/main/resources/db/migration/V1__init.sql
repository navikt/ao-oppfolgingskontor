CREATE TABLE arbeidsoppfolgingskontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontorId VARCHAR(4),
    endretAv VARCHAR(20),
    endretAvType VARCHAR(20),
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE arenakontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontorId VARCHAR(4),
    endretAv VARCHAR(20),
    endretAvType VARCHAR(20),
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE geografisktilknytningkontor (
    fnr VARCHAR(11) PRIMARY KEY,
    kontorId VARCHAR(4),
    endretAv VARCHAR(20),
    endretAvType VARCHAR(20),
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE aktorid (
    fnr VARCHAR(11) PRIMARY KEY,
    aktorid VARCHAR(13)
);

CREATE TABLE kontorhistorikk (
    id SERIAL PRIMARY KEY,
    fnr VARCHAR(11),
    kontorid VARCHAR(4),
    endretAv VARCHAR(20),
    endretAvType VARCHAR(20),
    kontorendringstype VARCHAR(255),
    -- "Triggere" for at bruker for tildelt et kontor
    -- Automatisk fordeling ved Arbeidssøkerregistrering
    -- Manuelt flyttet av veileder
    -- Endret i Arena
    -- Endret skjermingsstatus
    -- Endret addressebeskyttelse
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);