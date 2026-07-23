package io.clroot.hibernate.reactive.spring.boot.autoconfigure

internal object ReactiveConnectionUrl {
    private val consumedParameters = setOf("sslmode", "currentSchema")
    private val postgresSchemes = setOf("postgresql", "postgres")

    fun fromJdbc(jdbcUrl: String): String {
        val reactiveUrl = jdbcUrl.removePrefix("jdbc:")
        if (reactiveUrl.substringBefore(':').lowercase() !in postgresSchemes) {
            return reactiveUrl
        }

        val queryStart = reactiveUrl.indexOf('?')
        if (queryStart < 0) {
            return reactiveUrl
        }

        val baseUrl = reactiveUrl.substring(0, queryStart)
        val queryAndFragment = reactiveUrl.substring(queryStart + 1)
        val fragmentStart = queryAndFragment.indexOf('#')
        val query = if (fragmentStart < 0) queryAndFragment else queryAndFragment.substring(0, fragmentStart)
        val fragment = if (fragmentStart < 0) "" else queryAndFragment.substring(fragmentStart)
        val retainedParameters = query
            .split('&')
            .filterNot { parameter -> parameter.substringBefore('=') in consumedParameters }

        return buildString {
            append(baseUrl)
            if (retainedParameters.isNotEmpty()) {
                append('?')
                append(retainedParameters.joinToString("&"))
            }
            append(fragment)
        }
    }
}
