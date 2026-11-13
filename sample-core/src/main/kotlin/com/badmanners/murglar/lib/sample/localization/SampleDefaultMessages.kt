package com.badmanners.murglar.lib.sample.localization

import com.badmanners.murglar.lib.core.localization.DefaultMessages


object SampleDefaultMessages : DefaultMessages(), SampleMessages {
    override val serviceName = "Local files"
}
