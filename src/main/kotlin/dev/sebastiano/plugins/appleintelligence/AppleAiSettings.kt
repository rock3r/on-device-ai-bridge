// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.UUID

private const val CREDENTIAL_SERVICE_NAME = "Apple On-Device AI"
private const val CREDENTIAL_USER_NAME = "api-key"

@Service(Service.Level.APP)
@State(name = "AppleAiSettings", storages = [Storage("apple-ai.xml")])
internal class AppleAiSettings : PersistentStateComponent<AppleAiSettings.State> {

    data class State(
        var host: String = "127.0.0.1",
        var port: Int = 8080,
        var autoStart: Boolean = false,
        var swiftHelperPath: String = "",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getApiKey(): String {
        return PasswordSafe.instance.getPassword(credentialAttributes()) ?: ""
    }

    fun setApiKey(apiKey: String) {
        PasswordSafe.instance.setPassword(credentialAttributes(), apiKey)
    }

    fun ensureApiKey(): String {
        var key = getApiKey()
        if (key.isBlank()) {
            key = UUID.randomUUID().toString()
            setApiKey(key)
        }
        return key
    }

    companion object {
        @JvmStatic fun getInstance(): AppleAiSettings = service()

        private fun credentialAttributes(): CredentialAttributes =
            CredentialAttributes(generateServiceName(CREDENTIAL_SERVICE_NAME, CREDENTIAL_USER_NAME))
    }
}
