package com.energiacontexto.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.energiacontexto.client.AppPreferences
import com.energiacontexto.server.ModelSummary
import com.energiacontexto.server.PolicyChoiceDto
import com.energiacontexto.server.SessionPrediction
import com.energiacontexto.server.SyncResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteSync(context: Context, private val prefs: AppPreferences) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun useRemote(): Boolean = prefs.loadServerUrl().isNotBlank()

    fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun postSync(sessionId: String, mode: String, profile: String, events: List<JSONObject>): SyncResponse {
        val base = prefs.loadServerUrl().trimEnd('/')
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("mode", mode)
            .put("profile", profile)
            .put("events", JSONArray(events))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("$base/api/sync").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val policy = if (json.isNull("policy")) null else {
                val p = json.getJSONObject("policy")
                PolicyChoiceDto(p.getInt("level"), p.getString("reason"))
            }
            val pred = if (json.isNull("prediction")) null else {
                val p = json.getJSONObject("prediction")
                SessionPrediction(p.getDouble("drop"), p.getString("model"), p.getLong("t"))
            }
            val m = json.getJSONObject("model")
            return SyncResponse(
                accepted = json.getInt("accepted"),
                policy = policy,
                prediction = pred,
                model = ModelSummary(m.getString("chosen"), m.getInt("samples")),
            )
        }
    }

    fun fetchStats(profile: String): JSONObject {
        val base = prefs.loadServerUrl().trimEnd('/')
        val url = if (profile.isEmpty()) "$base/api/stats" else "$base/api/stats?profile=$profile"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    fun postTrain(): JSONObject {
        val base = prefs.loadServerUrl().trimEnd('/')
        val req = Request.Builder().url("$base/api/train")
            .post("".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    fun postReset() {
        val base = prefs.loadServerUrl().trimEnd('/')
        val req = Request.Builder().url("$base/api/reset")
            .post("".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        }
    }
}
