alter table arbeidsoppfolgingskontor
    add column oppfolgingsperiode_id UUID null,
    add constraint fk_arbeidsoppfolgingskontor_oppfolgingsperiode foreign key (oppfolgingsperiode_id) references oppfolgingsperiode(oppfolgingsperiode_id);

CREATE INDEX idx_arbeidsoppfolgingskontor_oppfolgingsperiode_id
    ON arbeidsoppfolgingskontor(oppfolgingsperiode_id);
