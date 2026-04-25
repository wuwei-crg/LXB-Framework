package com.example.lxb_ignition.storage

import android.content.Context
import java.io.File

object AppStatePaths {

    private const val STATE_DIR_NAME = "lxb_state"

    fun getStateBaseDir(context: Context): File {
        val externalBase = context.getExternalFilesDir(null)?.absolutePath
        val base = if (!externalBase.isNullOrBlank()) {
            File(externalBase, STATE_DIR_NAME)
        } else {
            File(context.filesDir, STATE_DIR_NAME)
        }
        if (!base.exists()) {
            runCatching { base.mkdirs() }
        }
        return base
    }

    fun getMapDir(context: Context): File {
        val dir = File(getStateBaseDir(context), "maps")
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir
    }


    fun getTaskMapRootDir(context: Context): File {
        val dir = File(getStateBaseDir(context), "task_maps")
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir
    }

    fun getLlmConfigPath(context: Context): String {
        val base = getStateBaseDir(context)
        return File(base, "lxb-llm-config.json").absolutePath
    }

    fun getTaskMemoryPath(context: Context): String {
        val base = getStateBaseDir(context)
        return File(base, "task_memory.json").absolutePath
    }
}
