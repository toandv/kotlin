/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// Old package for compatibility
@file:Suppress("PackageDirectoryMismatch")

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.jetbrains.kotlin.compilerRunner.KonanLibraryGenerationRunner
import org.jetbrains.kotlin.compilerRunner.konanHome
import org.jetbrains.kotlin.gradle.dsl.NativeCacheKind
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.targets.native.DisabledNativeTargetsReporter
import org.jetbrains.kotlin.gradle.tasks.CacheBuilder
import org.jetbrains.kotlin.gradle.tasks.CacheBuilder.Companion.DEFAULT_CACHE_KIND
import org.jetbrains.kotlin.gradle.tasks.addArg
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.gradle.utils.SingleWarningPerBuild
import org.jetbrains.kotlin.gradle.utils.lifecycleWithDuration
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.customerDistribution
import java.io.File

abstract class AbstractKotlinNativeTargetPreset<T : KotlinNativeTarget>(
    private val name: String,
    val project: Project,
    val konanTarget: KonanTarget,
    protected val kotlinPluginVersion: String
) : KotlinTargetPreset<T> {

    init {
        // This is required to obtain Kotlin/Native home in CLion plugin:
        setupNativeHomePrivateProperty()
    }

    override fun getName(): String = name

    private fun setupNativeHomePrivateProperty() = with(project) {
        if (!hasProperty(KOTLIN_NATIVE_HOME_PRIVATE_PROPERTY))
            extensions.extraProperties.set(KOTLIN_NATIVE_HOME_PRIVATE_PROPERTY, konanHome)
    }

    private val isKonanHomeOverridden: Boolean
        get() = PropertiesProvider(project).nativeHome != null

    private fun generatePlatformLibsIfNeeded() = with(project) {
        // TODO: Cache info about generated klibs.
        val distribution = customerDistribution(konanHome)
        val presentPlatformLibs = platformLibs(konanTarget).map { it.nameWithoutExtension }.toSet()

        val defDirectory = File(distribution.platformDefs(konanTarget.family)).takeIf { it.isDirectory } ?: return
        val presentDefs = defDirectory.listFiles()!!.map { it.nameWithoutExtension }.toSet()

        // TODO: Check that all directories returned by platformLibs(konanTarget) are real klibs when klib componentization is merged.
        val platformLibsAreReady = presentDefs.all { it in presentPlatformLibs }

        if (!platformLibsAreReady) {
            logger.lifecycle("Generate platform libraries for $konanTarget...")
            logger.lifecycleWithDuration("Generate platform libraries for $konanTarget finished,") {
                // TODO: Uncomment
                //val logLevel = if (logger.isInfoEnabled) "debug" else "normal"
                val logLevel = "debug"
                val args = mutableListOf(
                    "-target", konanTarget.visibleName,
                    "-log-level", logLevel
                )

                // We can generate caches using either [CacheBuilder] or the library generator. Using CacheBuilder allows
                // keeping all the caching logic in one place while the library generator speeds up building caches because
                // it works in parallel. We use the library generator for now due to performance reasons.
                //
                // TODO: Supporting Gradle Worker API in the CacheBuilder and enabling the compiler daemon by default
                //       will allow switching to the CacheBuilder without performance penalty.
                if (DEFAULT_CACHE_KIND != NativeCacheKind.NONE) {
                    args.addArg("-cache-kind", DEFAULT_CACHE_KIND.produce!!)
                    args.addArg(
                        "-cache-directory",
                        CacheBuilder.getRootCacheDirectory(File(konanHome), konanTarget, true, DEFAULT_CACHE_KIND).absolutePath
                    )
                    args.addArg("-cache-arg", "-g")
                }

                KonanLibraryGenerationRunner(this).run(args)
            }
        }
    }

    private fun setupNativeCompiler() = with(project) {
        if (!isKonanHomeOverridden) {
            NativeCompilerDownloader(this).downloadIfNeeded()
            logger.info("Kotlin/Native distribution: $konanHome")
        } else {
            logger.info("User-provided Kotlin/Native distribution: $konanHome")
        }
        generatePlatformLibsIfNeeded()
    }

    private fun nativeLibrariesList(directory: String) = with(project) {
        file("$konanHome/klib/$directory")
            .listFiles { file -> file.isDirectory }
            ?.sortedBy { dir -> dir.name.toLowerCase() }
    }

    // We declare default K/N dependencies (default and platform libraries) as files to avoid searching them in remote repos (see KT-28128).
    private fun defaultLibs(stdlibOnly: Boolean = false): List<File> {
        var filesList = nativeLibrariesList("common")
        if (stdlibOnly) {
            filesList = filesList?.filter { dir -> dir.name == "stdlib" }
        }
        return filesList.orEmpty()
    }

    private fun platformLibs(target: KonanTarget): List<File> =
        nativeLibrariesList("platform/${target.name}").orEmpty()

    private fun Collection<File>.asDependencies(): List<Dependency> = with(project) {
        map { dir -> dependencies.create(files(dir)) }
    }

    protected abstract fun createTargetConfigurator(): KotlinTargetConfigurator<T>

    protected abstract fun instantiateTarget(name: String): T

    override fun createTarget(name: String): T {
        setupNativeCompiler()

        val result = instantiateTarget(name).apply {
            targetName = name
            disambiguationClassifier = name
            preset = this@AbstractKotlinNativeTargetPreset

            val compilationFactory = KotlinNativeCompilationFactory(project, this)
            compilations = project.container(compilationFactory.itemClass, compilationFactory)
        }

        createTargetConfigurator().configureTarget(result)

        addDependenciesOnLibrariesFromDistribution(result)

        if (!konanTarget.enabledOnCurrentHost) {
            with(HostManager()) {
                val supportedHosts = enabledByHost.filterValues { konanTarget in it }.keys
                DisabledNativeTargetsReporter.reportDisabledTarget(project, result, supportedHosts)
            }
        }

        return result
    }

    // Allow IDE to resolve the libraries provided by the compiler by adding them into dependencies.
    private fun addDependenciesOnLibrariesFromDistribution(result: T) {
        result.compilations.all { compilation ->
            val target = compilation.konanTarget
            project.whenEvaluated {
                // First, put common libs:
                defaultLibs(!compilation.enableEndorsedLibs).asDependencies().forEach {
                    project.dependencies.add(compilation.compileDependencyConfigurationName, it)
                }
                // Then, platform-specific libs:
                platformLibs(target).asDependencies().forEach {
                    project.dependencies.add(compilation.compileDependencyConfigurationName, it)
                }
            }
        }

        // Add dependencies to stdlib-native for intermediate single-backend source-sets (like 'allNative')
        project.whenEvaluated {
            val compilationsBySourceSets = CompilationSourceSetUtil.compilationsBySourceSets(this)

            fun KotlinSourceSet.isIntermediateNativeSourceSet(): Boolean {
                val compilations = compilationsBySourceSets[this] ?: return false

                if (compilations.all { it.defaultSourceSet == this })
                    return false

                return compilations.all { it.target.platformType == KotlinPlatformType.native }
            }

            val stdlib = defaultLibs(stdlibOnly = true).asDependencies().singleOrNull() ?: run {
                warnAboutMissingNativeStdlib(project)
                return@whenEvaluated
            }

            project.kotlinExtension.sourceSets
                .filter { it.isIntermediateNativeSourceSet() }
                .forEach {
                    it.dependencies { implementation(stdlib) }
                }
        }
    }

    companion object {
        private const val KOTLIN_NATIVE_HOME_PRIVATE_PROPERTY = "konanHome"

        private fun warnAboutMissingNativeStdlib(project: Project) {
            if (!project.hasProperty("kotlin.native.nostdlib")) {
                SingleWarningPerBuild.show(
                    project,
                    buildString {
                        append(NO_NATIVE_STDLIB_WARNING)
                        if (PropertiesProvider(project).nativeHome != null)
                            append(NO_NATIVE_STDLIB_PROPERTY_WARNING)
                    }
                )
            }
        }

        internal const val NO_NATIVE_STDLIB_WARNING =
            "The Kotlin/Native distribution used in this build does not provide the standard library. "

        internal const val NO_NATIVE_STDLIB_PROPERTY_WARNING =
            "Make sure that the '${PropertiesProvider.KOTLIN_NATIVE_HOME}' property points to a valid Kotlin/Native distribution."
    }

}

open class KotlinNativeTargetPreset(name: String, project: Project, konanTarget: KonanTarget, kotlinPluginVersion: String) :
    AbstractKotlinNativeTargetPreset<KotlinNativeTarget>(name, project, konanTarget, kotlinPluginVersion) {

    override fun createTargetConfigurator(): KotlinTargetConfigurator<KotlinNativeTarget> =
        KotlinNativeTargetConfigurator(kotlinPluginVersion)

    override fun instantiateTarget(name: String): KotlinNativeTarget {
        return project.objects.newInstance(KotlinNativeTarget::class.java, project, konanTarget)
    }
}

open class KotlinNativeTargetWithHostTestsPreset(name: String, project: Project, konanTarget: KonanTarget, kotlinPluginVersion: String) :
    AbstractKotlinNativeTargetPreset<KotlinNativeTargetWithHostTests>(name, project, konanTarget, kotlinPluginVersion) {

    override fun createTargetConfigurator(): KotlinNativeTargetWithHostTestsConfigurator =
        KotlinNativeTargetWithHostTestsConfigurator(kotlinPluginVersion)

    override fun instantiateTarget(name: String): KotlinNativeTargetWithHostTests =
        project.objects.newInstance(KotlinNativeTargetWithHostTests::class.java, project, konanTarget)
}

open class KotlinNativeTargetWithSimulatorTestsPreset(name: String, project: Project, konanTarget: KonanTarget, kotlinPluginVersion: String) :
    AbstractKotlinNativeTargetPreset<KotlinNativeTargetWithSimulatorTests>(name, project, konanTarget, kotlinPluginVersion) {

    override fun createTargetConfigurator(): KotlinNativeTargetWithSimulatorTestsConfigurator =
        KotlinNativeTargetWithSimulatorTestsConfigurator(kotlinPluginVersion)

    override fun instantiateTarget(name: String): KotlinNativeTargetWithSimulatorTests =
        project.objects.newInstance(KotlinNativeTargetWithSimulatorTests::class.java, project, konanTarget)
}

internal val KonanTarget.isCurrentHost: Boolean
    get() = this == HostManager.host

internal val KonanTarget.enabledOnCurrentHost
    get() = HostManager().isEnabled(this)

internal val KotlinNativeCompilation.isMainCompilation: Boolean
    get() = name == KotlinCompilation.MAIN_COMPILATION_NAME
