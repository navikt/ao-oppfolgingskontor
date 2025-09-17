package db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.Npid
import org.junit.jupiter.api.Test

class IdentTest {

    @Test
    fun `Fnr skal kreve riktig dato`() {
        shouldThrow<Throwable> { Fnr("", AKTIV) }
        shouldThrow<Throwable> { Fnr("1234567890123", AKTIV) }
        shouldThrow<Throwable> { Fnr("abc", AKTIV) }
    }

    @Test
    fun `Dnr skal kreve riktig dato`() {
        shouldThrow<Throwable> { Dnr("", AKTIV) }
        shouldThrow<Throwable> { Dnr("1234567890123", AKTIV) }
        shouldThrow<Throwable> { Dnr("abc", AKTIV) }
        shouldThrow<Throwable> { Dnr("12345678901", AKTIV) }
    }

    @Test
    fun `Npid skal kreve riktig dato`() {
        shouldThrow<Throwable> { Npid("", AKTIV) }
        shouldThrow<Throwable> { Npid("1234567890123", AKTIV) }
        shouldThrow<Throwable> { Npid("abc", AKTIV) }
    }

    @Test
    fun `AktorId skal kreve riktig dato`() {
        shouldThrow<Throwable> { AktorId("", AKTIV) }
        shouldThrow<Throwable> { AktorId("12345678901", AKTIV) }
        shouldThrow<Throwable> { AktorId("abc", AKTIV) }
    }

    @Test
    fun `Ident of`() {
        Ident.of("12125678901", AKTIV).shouldBeInstanceOf<Fnr>()
        Ident.of("42125678901", AKTIV).shouldBeInstanceOf<Dnr>()
        Ident.of("42525678901", AKTIV).shouldBeInstanceOf<Dnr>()
        Ident.of("42625678901", AKTIV).shouldBeInstanceOf<Dnr>()
        Ident.of("42125678901", AKTIV).shouldBeInstanceOf<Dnr>()
        Ident.of("12325678901", AKTIV).shouldBeInstanceOf<Npid>()
        shouldThrow<Throwable> { Ident.of("32125678901", AKTIV) }
        Ident.of("1232567890121", AKTIV).shouldBeInstanceOf<AktorId>()
    }

}