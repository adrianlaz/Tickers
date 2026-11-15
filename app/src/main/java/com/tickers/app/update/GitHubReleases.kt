package com.tickers.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubApi {
    /** Latest non-draft, non-prerelease release. 404 if the repo has none. */
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(@Path("owner") owner: String, @Path("repo") repo: String): GitHubRelease
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** The APK to install: first asset ending in .apk. */
    val apk: GitHubAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
)

object GitHub {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Separate client from Yahoo's: different host, headers and timeouts. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Tickers-Android")
                    .build()
            )
        }
        .build()

    val api: GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)
}
