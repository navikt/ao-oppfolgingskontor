CREATE TABLE oppfolgingsperiode (
    fnr VARCHAR(11) PRIMARY KEY,
    oppfolgingsperiode_id UUID NOT NULL,
    start_dato TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
