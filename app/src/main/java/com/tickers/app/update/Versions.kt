package com.tickers.app.update

/** Compares dotted versions like "0.10.1" and tags like "v0.2.0" as integer tuples. */
object Versions {

    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** True if [candidate] is strictly newer than [current]. Unparseable input is never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
