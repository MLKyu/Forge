package com.mingeek.forge.data.catalog

/**
 * Domain-shaped errors raised by [ModelCatalogSource]. The catalog source
 * speaks Retrofit/OkHttp under the hood, but we don't want feature modules
 * to import retrofit2 just to render a friendly message — so we translate
 * HTTP statuses + IO failures into this sealed family at the data-layer
 * boundary.
 */
sealed class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Repo requires HF auth (token + accepted license on huggingface.co). */
    class Gated(message: String, cause: Throwable? = null) : CatalogException(message, cause)

    /** Repo id resolved to no HF record (renamed/deleted/typo). */
    class NotFound(message: String, cause: Throwable? = null) : CatalogException(message, cause)

    /** Connectivity / DNS / TLS — anything that didn't reach an HTTP response. */
    class Network(message: String, cause: Throwable? = null) : CatalogException(message, cause)
}
