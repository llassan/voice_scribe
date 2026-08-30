package com.vikash.voicescribe

import android.app.Application
import com.vikash.voicescribe.data.RecordingStore
import com.vikash.voicescribe.model.LanguagePrefs
import com.vikash.voicescribe.model.ModelManager
import com.vikash.voicescribe.transcribe.TranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var store: RecordingStore
        private set
    lateinit var models: ModelManager
        private set
    lateinit var languages: LanguagePrefs
        private set
    lateinit var engine: TranscriptionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(this)
        models = ModelManager(this)
        languages = LanguagePrefs(this)
        engine = TranscriptionEngine(this, store, models, languages, appScope)
    }
}
