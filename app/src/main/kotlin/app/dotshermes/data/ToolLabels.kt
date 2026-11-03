package app.dotshermes.data

import app.dotshermes.R

/**
 * Libellé de chaque outil Hermes connu (même liste que `_TOOL_LABELS` du plugin « dots »), à
 * traduire via les ressources. Un outil absent garde le libellé envoyé par le plugin.
 */
internal val ToolLabels: Map<String, Int> = mapOf(
    "terminal" to R.string.tool_terminal,
    "process" to R.string.tool_process,
    "read_file" to R.string.tool_read_file,
    "search_files" to R.string.tool_search_files,
    "write_file" to R.string.tool_write_file,
    "patch" to R.string.tool_patch,
    "web_search" to R.string.tool_web_search,
    "web_extract" to R.string.tool_web_extract,
    "skill_view" to R.string.tool_skill_view,
    "skills_list" to R.string.tool_skills_list,
    "skill_manage" to R.string.tool_skill_manage,
    "memory" to R.string.tool_memory,
    "session_search" to R.string.tool_session_search,
    "execute_code" to R.string.tool_execute_code,
    "delegate_task" to R.string.tool_delegate_task,
    "todo" to R.string.tool_todo,
    "cronjob" to R.string.tool_cronjob,
    "vision_analyze" to R.string.tool_vision_analyze,
    "image_generate" to R.string.tool_image_generate,
    "text_to_speech" to R.string.tool_text_to_speech,
    "send_message" to R.string.tool_send_message,
    "tool_search" to R.string.tool_tool_search,
    "tool_describe" to R.string.tool_tool_describe,
    "browser_request_takeover" to R.string.tool_browser_request_takeover,
)

/** Libellé traduit d'un outil, `null` s'il est inconnu ; les autres outils `browser_*` naviguent. */
internal fun toolLabel(name: String): Int? =
    ToolLabels[name] ?: R.string.tool_browser.takeIf { name.startsWith("browser_") }
