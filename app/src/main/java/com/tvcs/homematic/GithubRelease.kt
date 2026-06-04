package com.tvcs.homematic

import org.json.JSONObject

data class GithubRelease(
    val tagName: String,
    val name: String?,
    val body: String?,
    val assets: List<GithubAsset>
) {
    companion object {
        fun fromJson(json: JSONObject): GithubRelease {
            val assets = mutableListOf<GithubAsset>()
            val assetsArray = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    assets.add(GithubAsset.fromJson(assetsArray.getJSONObject(i)))
                }
            }
            return GithubRelease(
                tagName = json.optString("tag_name", ""),
                name    = json.optString("name").ifEmpty { null },
                body    = json.optString("body").ifEmpty { null },
                assets  = assets
            )
        }
    }
}

data class GithubAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String
) {
    companion object {
        fun fromJson(json: JSONObject) = GithubAsset(
            name        = json.optString("name", ""),
            size        = json.optLong("size", 0L),
            downloadUrl = json.optString("browser_download_url", "")
        )
    }
}
