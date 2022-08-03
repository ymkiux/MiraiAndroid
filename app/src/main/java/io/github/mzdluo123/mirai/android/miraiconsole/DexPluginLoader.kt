@file:Suppress(
    "EXPERIMENTAL_API_USAGE",
    "DEPRECATION_ERROR",
    "OverridingDeprecatedMember",
    "INVISIBLE_REFERENCE",
    "INVISIBLE_MEMBER"
)


package io.github.mzdluo123.mirai.android.miraiconsole

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.data.PluginDataStorage
import net.mamoe.mirai.console.internal.MiraiConsoleImplementationBridge
import net.mamoe.mirai.console.internal.plugin.ExportManagerImpl
import net.mamoe.mirai.console.internal.plugin.JvmPluginInternal
import net.mamoe.mirai.console.internal.util.PluginServiceHelper.findServices
import net.mamoe.mirai.console.internal.util.PluginServiceHelper.loadAllServices
import net.mamoe.mirai.console.plugin.jvm.*
import net.mamoe.mirai.console.plugin.loader.AbstractFilePluginLoader
import net.mamoe.mirai.console.plugin.loader.PluginLoadException
import net.mamoe.mirai.console.plugin.name
import net.mamoe.mirai.console.util.CoroutineScopeUtils.childScope
import net.mamoe.mirai.utils.MiraiInternalApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import net.mamoe.mirai.console.internal.data.MultiFilePluginDataStorageImpl
import java.nio.file.Path

/**
 * copy from net.mamoe.mirai.console.internal.plugin.BuiltInJvmPluginLoaderImpl
 * */

class DexPluginLoader(val odexPath: String,private val workingDir:Path) :
    AbstractFilePluginLoader<JvmPlugin, JvmPluginDescription>(".jar"),
    CoroutineScope by MiraiConsole.childScope(
        "DexPluginLoader",
        CoroutineExceptionHandler { _, throwable ->
            MiraiAndroidLogger.error(
                "Unhandled Jar plugin exception: ${throwable.message}",
                throwable
            )
        }),
    JvmPluginLoader {

    override val configStorage: PluginDataStorage
        get() =MultiFilePluginDataStorageImpl(workingDir.resolve("config"))


    override val dataStorage: PluginDataStorage
        get() =MultiFilePluginDataStorageImpl(workingDir.resolve("data"))

    @MiraiInternalApi
    override val classLoaders: MutableList<DexPluginClassLoader> = mutableListOf()

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER") // doesn't matter
    override fun getPluginDescription(plugin: JvmPlugin): JvmPluginDescription = plugin.description

    private val pluginFileToInstanceMap: MutableMap<File, JvmPlugin> = ConcurrentHashMap()

    @MiraiInternalApi
    override fun Sequence<File>.extractPlugins(): List<JvmPlugin> {
        ensureActive()

        fun Sequence<Map.Entry<File, DexPluginClassLoader>>.findAllInstances(): Sequence<Map.Entry<File, JvmPlugin>> {
            return onEach { (_, pluginClassLoader) ->
                val exportManagers = pluginClassLoader.findServices(
                    ExportManager::class
                ).loadAllServices()
                if (exportManagers.isEmpty()) {
                    val rules = pluginClassLoader.getResourceAsStream("export-rules.txt")
                    if (rules == null)
                        pluginClassLoader.declaredFilter = StandardExportManagers.AllExported
                    else rules.bufferedReader(Charsets.UTF_8).useLines {
                        pluginClassLoader.declaredFilter = ExportManagerImpl.parse(it.iterator())
                    }
                } else {
                    pluginClassLoader.declaredFilter = exportManagers[0]
                }
            }.map { (f, pluginClassLoader) ->
                f to pluginClassLoader.findServices(
                    JvmPlugin::class,
                    KotlinPlugin::class,
                    JavaPlugin::class
                ).loadAllServices()
            }.flatMap { (f, list) ->

                list.associateBy { f }.asSequence()
            }
        }


        val filePlugins = (this + ApkPluginLoader.apkPluginFile()).filterNot {
            pluginFileToInstanceMap.containsKey(it)
        }.associateWith {
            // DexClassLoader(it.absolutePath,odexPath,it.path,MiraiConsole::class.java.classLoader )
            DexPluginClassLoader(it, odexPath, MiraiConsole::class.java.classLoader, classLoaders)
        }.onEach { (_, classLoader) ->
            classLoaders.add(classLoader)
        }.asSequence().findAllInstances().onEach {
            //logger.verbose { "Successfully initialized JvmPlugin ${loaded}." }
        }.onEach { (file, plugin) ->
            pluginFileToInstanceMap[file] = plugin
        } + pluginFileToInstanceMap.asSequence()

        return filePlugins.toSet().map { it.value }
    }

    private val loadedPlugins = ConcurrentHashMap<JvmPlugin, Unit>()

    @Throws(PluginLoadException::class)
    override fun load(plugin: JvmPlugin) {
        ensureActive()

        if (loadedPlugins.put(plugin, Unit) != null) {
            error("Plugin '${plugin.name}' is already loaded and cannot be reloaded.")
        }
        runCatching {
            check(plugin is JvmPluginInternal) { "A JvmPlugin must extend AbstractJvmPlugin to be loaded by JvmPluginLoader.BuiltIn" }
            plugin.internalOnLoad()
        }.getOrElse {
            throw PluginLoadException("Exception while loading ${plugin.description.name}", it)
        }
    }

    override fun enable(plugin: JvmPlugin) {
        if (plugin.isEnabled) error("Plugin '${plugin.name}' is already enabled and cannot be re-enabled.")
        ensureActive()
        runCatching {
            if (plugin is JvmPluginInternal) {
                plugin.internalOnEnable()
            } else plugin.onEnable()
        }.getOrElse {
            throw PluginLoadException("Exception while loading ${plugin.description.name}", it)
        }
    }

    @MiraiInternalApi
    override fun findLoadedClass(name: String): Class<*>? {
        TODO("Not yet implemented")
    }

    override fun disable(plugin: JvmPlugin) {
        if (!plugin.isEnabled) error("Plugin '${plugin.name}' is not already disabled and cannot be re-disabled.")

        if (MiraiConsole.isActive)
            ensureActive()

        if (plugin is JvmPluginInternal) {
            plugin.internalOnDisable()
        } else plugin.onDisable()
    }
}