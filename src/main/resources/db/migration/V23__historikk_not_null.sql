ALTER TABLE arbeidsoppfolgingskontor
    ALTER COLUMN historikk_entry SET NOT NULL;

ALTER TABLE geografisktilknytningkontor
    ALTER COLUMN historikk_entry SET NOT NULL;

-- Kan ikke sette arena sin historikk_entry til not null fordi vi har ikke
-- en komplett historikk