package com.mingeek.forge.data.catalog.huggingface

import okhttp3.Interceptor
import okhttp3.Response

class HfAuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val req = if (!token.isNullOrBlank() && chain.request().header("Authorization") == null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(req)
    }
}
