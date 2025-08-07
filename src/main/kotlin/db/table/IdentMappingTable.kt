package db.table

import jdk.internal.org.jline.utils.ExecHelper.exec
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestampWithTimeZone

object IdentMappingTable: IdTable<String>("ident_mapping") {
    override val id = char("ident", 20).entityId()
    override val primaryKey = PrimaryKey(id)
    val identType = char("ident_type", 11)
    val historisk = bool("historisk")
    val internIdent = long("intern_ident")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}

val InternIdentSequence = Sequence(
    name = "intern_ident_seq",
)

fun Transaction.nextValueOf(sequence: Sequence): Long = exec("SELECT nextval('${sequence.identifier}');") { resultSet ->
    if (resultSet.next().not()) {
        throw Error("Missing nextValue in resultSet of sequence '${sequence.identifier}'")
    }
    else {
        resultSet.getLong(1)
    }
} ?: throw Error("Unable to get nextValue of sequence '${sequence.identifier}'")