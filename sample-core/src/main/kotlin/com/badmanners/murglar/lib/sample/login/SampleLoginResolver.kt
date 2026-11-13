package com.badmanners.murglar.lib.sample.login

import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant
import com.badmanners.murglar.lib.core.login.LoginResolver
import com.badmanners.murglar.lib.core.login.WebLoginVariant
import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.sample.localization.SampleMessages


class SampleLoginResolver(
    private val messages: SampleMessages
) : LoginResolver {

    override val isLogged: Boolean
        get() = true

    override val loginInfo: String
        get() = messages.serviceName

    override val webLoginVariants = emptyList<WebLoginVariant>()

    override val credentialsLoginVariants = emptyList<CredentialsLoginVariant>()

    override suspend fun webLogin(loginVariantId: String, webViewProvider: WebViewProvider): Boolean =
        error("Login is not supported for local files plugin")

    override suspend fun credentialsLogin(loginVariantId: String, args: Map<String, String>): CredentialLoginStep =
        error("Login is not supported for local files plugin")

    override fun logout() {
    }
}
