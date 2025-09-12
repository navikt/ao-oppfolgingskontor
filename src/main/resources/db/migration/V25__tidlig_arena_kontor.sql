create table tidlig_arena_kontor (
    ident VARCHAR(11) PRIMARY KEY,
    kontor_id VARCHAR(4),
    siste_endret_dato TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
)
