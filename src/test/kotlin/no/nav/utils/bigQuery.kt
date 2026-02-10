package no.nav.utils

import eventsLogger.BigQueryClient
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.jetbrains.exposed.sql.Database

val bigQueryClient = BigQueryClient(
    "ProjectId",
    ExposedLockProvider(Database.connect(TestDb.postgres))
)