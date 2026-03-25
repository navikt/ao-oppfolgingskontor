package no.nav.db.table

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.core.java.javaUUID

object OppfolgingsperiodeTable : IdTable<String>("oppfolgingsperiode") {
    override val id = varchar("fnr", 11).entityId() // VARCHAR(11) PRIMARY KEY,
    override val primaryKey = PrimaryKey(id) // PRIMARY KEY (fnr),
    val oppfolgingsperiodeId = javaUUID("oppfolgingsperiode_id") // UUID NOT NULL,
    val startDato = timestampWithTimeZone("start_dato") // TIMESTAMPTZ NOT NULL,
    val createdAt =
            timestampWithTimeZone("created_at")
                    .defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMPTZ DEFAULT NOW(),
    val updatedAt =
            timestampWithTimeZone("updated_at")
                    .defaultExpression(CurrentTimestampWithTimeZone) // TIMESTAMPTZ DEFAULT NOW()
}
