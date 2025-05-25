package com.google.ai.edge.gallery.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String,
    val score: Double
)

data class TavilySearchResponse(
    val answer: String?,
    val query: String?,
    val results: List<TavilySearchResult>?
)

class WebSearchService {

    private val client = OkHttpClient()

    suspend fun search(apiKey: String, query: String): TavilySearchResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonRequestBody = JSONObject().apply {
                    put("api_key", apiKey)
                    put("query", query)
                    put("search_depth", "basic")
                    put("include_answer", true)
                    put("max_results", 3)
                    // include_domains and exclude_domains are empty by default
                }.toString()

                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(jsonRequestBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("WebSearchService", "API Error: ${response.code} ${response.message}")
                        return@withContext null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        Log.e("WebSearchService", "Empty response body")
                        return@withContext null
                    }

                    parseTavilyResponse(responseBody)
                }
            } catch (e: IOException) {
                Log.e("WebSearchService", "Network Error: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e("WebSearchService", "Error during search: ${e.message}", e)
                null
            }
        }
    }

    private fun parseTavilyResponse(responseBody: String): TavilySearchResponse? {
        return try {
            val jsonObject = JSONObject(responseBody)
            val answer = jsonObject.optString("answer", null)
            val query = jsonObject.optString("query", null)

            val resultsArray = jsonObject.optJSONArray("results")
            val searchResults = mutableListOf<TavilySearchResult>()
            if (resultsArray != null) {
                for (i in 0 until resultsArray.length()) {
                    val resultObj = resultsArray.getJSONObject(i)
                    searchResults.add(
                        TavilySearchResult(
                            title = resultObj.getString("title"),
                            url = resultObj.getString("url"),
                            content = resultObj.getString("content"),
                            score = resultObj.getDouble("score")
                        )
                    )
                }
            }
            TavilySearchResponse(answer, query, if (searchResults.isEmpty()) null else searchResults)
        } catch (e: Exception) {
            Log.e("WebSearchService", "Error parsing JSON response: ${e.message}", e)
            null
        }
    }
}
