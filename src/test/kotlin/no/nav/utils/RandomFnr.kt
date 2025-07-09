package no.nav.utils

import no.nav.db.Fnr

fun randomFnr(): Fnr {
    val randomDigits = (1..11).map { (0..9).random() }.joinToString("")
    return Fnr(randomDigits)
}