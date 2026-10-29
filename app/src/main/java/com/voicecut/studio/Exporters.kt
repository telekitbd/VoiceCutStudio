package com.voicecut.studio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object Exporters {
    fun ts(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms % 1000)
    }

    fun txt(list: List<Segment>) =
        list.joinToString("\n") { "[${ts(it.startMs)}] ${it.speaker}: ${it.text}" }

    fun srt(list: List<Segment>) = list.mapIndexed { i, s ->
        "${i + 1}\n${srtTime(s.startMs)} --> ${srtTime(s.endMs)}\n${s.speaker}: ${s.text}\n"
    }.joinToString("\n")

    fun json(list: List<Segment>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("speaker", it.speaker).put("start_ms", it.startMs)
                    .put("end_ms", it.endMs).put("text", it.text)
            )
        }
        return JSONObject().put("segments", arr).toString(2)
    }
}
