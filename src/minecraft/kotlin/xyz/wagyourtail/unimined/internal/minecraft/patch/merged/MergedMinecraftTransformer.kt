package xyz.wagyourtail.unimined.internal.minecraft.patch.merged

import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.minecraft.patch.*
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessTransformerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.CraftbukkitPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.SpigotPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModAgentPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.widener.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.CraftbukkitMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.SpigotMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.BabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.LegacyFabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.OfficialFabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.QuiltMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.MinecraftForgeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.NeoForgedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.MustSet
import java.nio.file.FileSystem
import java.nio.file.Path

class MergedMinecraftTransformer(project: Project, provider: MinecraftProvider): AbstractMinecraftTransformer(project, provider, "merged"), MergedPatcher {

    val patchers = mutableListOf<AbstractMinecraftTransformer>()

    var customLibraryFilter: (Library) -> Boolean by FinalizeOnRead(MustSet())

    override var libraryFilter: MergedPatcher.LibraryFilter by FinalizeOnRead(MergedPatcher.LibraryFilter.ALL)

    override var onMergeFail: (clientNode: ClassNode, serverNode: ClassNode, fs: FileSystem, exception: Exception) -> Unit
        get() = patchers.first().onMergeFail
        set(value) {
            patchers.first().onMergeFail = value
        }

    override var canCombine: Boolean
        get() = patchers.first().canCombine
        set(value) {
            patchers.forEach { it.canCombine = value }
        }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        val mc = patchers.first().merge(clientjar, serverjar)
        if (mc.mappingNamespace != provider.mappings.OFFICIAL) {
            // remap back to official
            return provider.minecraftRemapper.provide(mc, provider.mappings.OFFICIAL, provider.mappings.OFFICIAL)
        }
        return mc
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        return patchers.fold(minecraft) {
            acc, patcher -> patcher.transform(acc)
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return patchers.fold(baseMinecraft) {
            acc, patcher -> patcher.afterRemap(acc)
        }
    }

    override fun beforeRemapJarTask(remapJarTask: RemapJarTask, input: Path): Path {
        return patchers.fold(input) {
            acc, patcher -> patcher.beforeRemapJarTask(remapJarTask, acc)
        }
    }

    override fun beforeMappingsResolve() {
        patchers.forEach { it.beforeMappingsResolve() }
    }

    override fun afterEvaluate() {
        patchers.forEach { it.afterEvaluate() }
    }

    override fun afterRemapJarTask(remapJarTask: RemapJarTask, output: Path) {
        patchers.forEach { it.afterRemapJarTask(remapJarTask, output) }
    }

    override fun applyExtraLaunches() {
        patchers.forEach { it.applyExtraLaunches() }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        patchers.forEach { it.applyClientRunTransform(config) }
    }

    override fun applyServerRunTransform(config: RunConfig) {
        patchers.forEach { it.applyServerRunTransform(config) }
    }

    override fun apply() {
        patchers.forEach { it.apply() }
    }

    @Deprecated("use prodNamespace instead", replaceWith = ReplaceWith("prodNamespace"))
    override fun setProdNamespace(namespace: String) {
        prodNamespace = provider.mappings.getNamespace(namespace)
    }

    override fun prodNamespace(namespace: String) {
        prodNamespace = provider.mappings.getNamespace(namespace)
    }

    override fun fabric(action: FabricLikePatcher.() -> Unit) {
        val fabric = OfficialFabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun legacyFabric(action: FabricLikePatcher.() -> Unit) {
        val fabric = LegacyFabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun babric(action: FabricLikePatcher.() -> Unit) {
        val fabric = BabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun quilt(action: FabricLikePatcher.() -> Unit) {
        val fabric = QuiltMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    @Deprecated("Please specify which forge.", replaceWith = ReplaceWith("minecraftForge(action)"))
    override fun forge(action: ForgeLikePatcher<*>.() -> Unit) {
        minecraftForge(action)
    }

    override fun minecraftForge(action: MinecraftForgePatcher<*>.() -> Unit) {
        val forge = MinecraftForgeMinecraftTransformer(project, provider)
        forge.action()
        patchers.add(forge)
    }

    override fun neoForged(action: NeoForgedPatcher<*>.() -> Unit) {
        val forge = NeoForgedMinecraftTransformer(project, provider)
        forge.action()
        patchers.add(forge)
    }

    override fun jarMod(action: JarModAgentPatcher.() -> Unit) {
        val jarMod = JarModAgentMinecraftTransformer(project, provider)
        jarMod.action()
        patchers.add(jarMod)
    }

    override fun accessWidener(action: AccessWidenerPatcher.() -> Unit) {
        val aw = AccessWidenerMinecraftTransformer(project, provider)
        aw.action()
        patchers.add(aw)
    }

    override fun accessTransformer(action: AccessTransformerPatcher.() -> Unit) {
        val at = AccessTransformerMinecraftTransformer(project, provider)
        at.action()
        patchers.add(at)
    }

    override fun craftBukkit(action: CraftbukkitPatcher.() -> Unit) {
        val cb = CraftbukkitMinecraftTransformer(project, provider)
        cb.action()
        patchers.add(cb)
    }

    override fun spigot(action: SpigotPatcher.() -> Unit) {
        val spigot = SpigotMinecraftTransformer(project, provider)
        spigot.action()
        patchers.add(spigot)
    }

    @ApiStatus.Experimental
    override fun <T : MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit) {
        mcPatcher.action()
        patchers.add(mcPatcher as AbstractMinecraftTransformer)
    }

    override fun customLibraryFilter(filter: (String) -> Boolean) {
        libraryFilter = MergedPatcher.LibraryFilter.CUSTOM
        customLibraryFilter = { filter(it.name) }
    }

    override fun libraryFilter(library: Library): Boolean {
        return when (libraryFilter) {
            MergedPatcher.LibraryFilter.ANY -> patchers.any { it.libraryFilter(library) }
            MergedPatcher.LibraryFilter.ALL -> patchers.all { it.libraryFilter(library) }
            MergedPatcher.LibraryFilter.CUSTOM -> customLibraryFilter(library)
        }
    }

}