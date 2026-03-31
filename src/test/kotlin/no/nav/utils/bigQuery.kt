package no.nav.utils

import eventsLogger.BigQueryClient
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.jetbrains.exposed.v1.jdbc.Database

val bigQueryClient = BigQueryClient(
    "ProjectId",
    ExposedLockProvider(Database.connect(TestDb.postgres))
)