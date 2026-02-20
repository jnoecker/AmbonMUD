package dev.ambon.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.addResourceSource

object AppConfigLoader {
    private const val DEFAULT_RESOURCE_PATH = "/application.yaml"

    fun load(
        resourcePath: String = DEFAULT_RESOURCE_PATH,
        extraSources: List<PropertySource> = emptyList(),
    ): AppConfig {
        var builder = ConfigLoaderBuilder.default()

        for (source in extraSources) {
            builder = builder.addSource(source)
        }
        builder = builder.addResourceSource(resourcePath, optional = false)

        return builder
            .build()
            .loadConfigOrThrow<QuickMudRootConfig>()
            .quickmud
            .validated()
    }
}
