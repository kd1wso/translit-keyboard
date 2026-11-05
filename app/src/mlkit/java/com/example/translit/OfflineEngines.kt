package com.example.translit

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/** Полная сборка: перевод без интернета на Google ML Kit. */
object OfflineEngines {
    val default: OfflineEngine = MlKitEngine
}

private object MlKitEngine : OfflineEngine {

    private const val DOWNLOADED_TTL_MS = 30_000L

    override val available = true

    private val languages: Set<String> by lazy {
        try {
            TranslateLanguage.getAllLanguages().toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    override fun supports(lang: String) = lang in languages

    private var downloadedCache: Set<String>? = null
    private var downloadedAt = 0L

    private fun <T> await(task: Task<T>, seconds: Long): T = Tasks.await(task, seconds, TimeUnit.SECONDS)

    @Synchronized
    override fun downloaded(): Set<String> {
        val now = System.currentTimeMillis()
        downloadedCache?.let { if (now - downloadedAt < DOWNLOADED_TTL_MS) return it }
        val set = try {
            await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java), 10)
                .map { it.language }.toSet() + "en"
        } catch (_: Throwable) {
            setOf("en")
        }
        downloadedCache = set
        downloadedAt = now
        return set
    }

    @Synchronized
    private fun forgetDownloaded() {
        downloadedCache = null
    }

    // переводчики держим открытыми (загрузка модели в память — заметная пауза), не больше двух
    private val clients = LinkedHashMap<String, Translator>()

    private fun client(source: String, target: String): Translator = synchronized(clients) {
        val key = "$source>$target"
        clients.remove(key)?.let { clients[key] = it; return it }
        val c = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        )
        clients[key] = c
        while (clients.size > 2) {
            val eldest = clients.keys.first()
            clients.remove(eldest)?.close()
        }
        c
    }

    override fun translate(text: String, source: String, target: String): String? = try {
        if (!supports(source) || !supports(target)) null
        else await(client(source, target).translate(text), 20)
    } catch (_: Throwable) {
        null
    }

    private val identifier: LanguageIdentifier? by lazy {
        try {
            LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
            )
        } catch (_: Throwable) {
            null
        }
    }

    override fun detect(text: String): String? = try {
        val id = identifier
        val tag = if (id == null) null else await(id.identifyLanguage(text), 5)
        if (tag == null || tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) null else tag
    } catch (_: Throwable) {
        null
    }

    override fun download(langs: List<String>, wifiOnly: Boolean): String? {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        val manager = try {
            RemoteModelManager.getInstance()
        } catch (e: Throwable) {
            return e.message ?: "ML Kit недоступен"
        }
        try {
            for (lang in langs) {
                if (lang == "en" || !supports(lang)) continue
                Tasks.await(manager.download(TranslateRemoteModel.Builder(lang).build(), conditions), 60, TimeUnit.MINUTES)
            }
            return null
        } catch (e: Throwable) {
            return (e.cause ?: e).message ?: "не удалось скачать"
        } finally {
            forgetDownloaded()
        }
    }

    override fun delete(lang: String): String? = try {
        await(RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build()), 30)
        synchronized(clients) {
            for (k in clients.keys.filter { it.startsWith("$lang>") || it.endsWith(">$lang") }) clients.remove(k)?.close()
        }
        null
    } catch (e: Throwable) {
        (e.cause ?: e).message ?: "не удалось удалить"
    } finally {
        forgetDownloaded()
    }
}
