DROP TABLE kontornavn;

CREATE TABLE kontornavn (
    kontor_id varchar(4) primary key,
    kontor_navn text,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
)
