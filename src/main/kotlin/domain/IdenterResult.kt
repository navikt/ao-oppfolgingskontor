package domain

import no.nav.db.Ident
import no.nav.db.InternIdent
import no.nav.db.finnForetrukketIdent

sealed class IdenterResult {
    fun getOrThrow(): IdenterFunnet {
        return when (this) {
            is IdenterFunnet -> this
            is IdenterIkkeFunnet -> throw Exception("Fikk ikke hentet identer for ident: ${this.message}")
            is IdenterOppslagFeil -> throw Exception("Fikk ikke hentet identer for ident: ${this.message}")
        }
    }
}
data class IdenterFunnet(val identer: List<Ident>, val inputIdent: Ident, val internIdent: InternIdent) : IdenterResult() {
    val foretrukketIdent: Ident
        get() = identer.finnForetrukketIdent()  ?: throw IllegalStateException("Fant ikke foretrukket ident, alle identer historiske?")
}
data class IdenterIkkeFunnet(val message: String) : IdenterResult()
data class IdenterOppslagFeil(val message: String) : IdenterResult()
