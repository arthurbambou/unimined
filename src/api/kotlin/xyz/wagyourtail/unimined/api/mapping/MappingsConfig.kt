package xyz.wagyourtail.unimined.api.mapping

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import net.fabricmc.tinyremapper.IMappingProvider
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig

/**
 * @since 1.0.0
 */
abstract class MappingsConfig(val project: Project, val minecraft: MinecraftConfig) {

    @set:ApiStatus.Internal
    @get:ApiStatus.Internal
    abstract var devNamespace: MappingNamespace

    @set:ApiStatus.Internal
    @get:ApiStatus.Internal
    abstract var devFallbackNamespace: MappingNamespace

    @get:ApiStatus.Internal
    abstract val mappingsDeps: MutableList<MappingDepConfig<*>>

    abstract var side: EnvType

    abstract val hasStubs: Boolean

    fun devNamespace(namespace: String) {
        devNamespace = MappingNamespace.getNamespace(namespace)
    }

    fun devFallbackNamespace(namespace: String) {
        devFallbackNamespace = MappingNamespace.getNamespace(namespace)
    }

    fun mojmap() {
        mojmap {}
    }

    abstract fun mojmap(action: MappingDepConfig<*>.() -> Unit)

    fun mojmap(
        @DelegatesTo(value = MappingDepConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mojmap {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun mapping(
        dependency: Any,
    ) {
        mapping(dependency) {}
    }

    abstract fun mapping(
        dependency: Any,
        action: MappingDepConfig<*>.() -> Unit
    )

    fun mapping(
        dependency: Any,
        @DelegatesTo(value = MappingDepConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mapping(dependency) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @ApiStatus.Experimental
    fun newNamespace(namespace: String, type: String) = MappingNamespace(namespace, MappingNamespace.Type.fromId(type))

    @ApiStatus.Internal
    abstract fun getTRMappings(
        remap: Pair<MappingNamespace, MappingNamespace>,
        remapLocals: Boolean = false
    ): (IMappingProvider.MappingAcceptor) -> Unit

    @get:ApiStatus.Internal
    abstract val available: Set<MappingNamespace>

    @get:ApiStatus.Internal
    abstract val combinedNames: String
}
