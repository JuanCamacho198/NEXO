package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule (SDD android-stack-modernization S2, R8 / SC9.1-9.2).
 *
 * Every `*ViewModel` must expose an explicit factory or a single public
 * constructor with no defaulted parameters. A defaulted parameter alone
 * generates a synthetic constructor that the framework ViewModelProvider cannot
 * reflectively resolve — this was NEXTPAGE-ANDROID-1 (NoSuchMethodException on
 * /settings -> Rendimiento).
 */
class ViewModelConstructionTest {
    private val viewModelSuffix = "ViewModel"
    private val factorySuffix = "Factory"
    private val factoryFunctionName = "factory"

    @Test
    fun `every ViewModel exposes an explicit factory or a single non-defaulted constructor`() {
        val scope = Konsist.scopeFromProduction()
        val classes = scope.classes()
        val factoryClassNames = classes.map { it.name }.toSet()
        val viewModels = classes.filter { it.name.endsWith(viewModelSuffix) }

        assertTrue(
            "Konsist found no production ViewModel classes; the production scope could not be resolved",
            viewModels.size >= MIN_EXPECTED_VIEW_MODELS,
        )

        val offenders = viewModels.filterNot { it.isConstructible(factoryClassNames) }

        if (offenders.isNotEmpty()) {
            fail(
                "Every *ViewModel must declare an explicit *Factory/factory or a single constructor " +
                    "without defaulted parameters (NEXTPAGE-ANDROID-1):\n" +
                    offenders.joinToString("\n") { "  - ${it.name} (${it.containingFile?.path})" },
            )
        }
    }

    private fun KoClassDeclaration.isConstructible(factoryClassNames: Set<String>): Boolean = hasExplicitFactory(factoryClassNames) || hasSingleNonDefaultedConstructor()

    private fun KoClassDeclaration.hasExplicitFactory(factoryClassNames: Set<String>): Boolean {
        val namedFactory = factoryClassNames.contains("$name$factorySuffix")
        val nestedFactory =
            classes().any { it.name.endsWith(factorySuffix) } ||
                objects(includeNested = false).any { obj -> obj.classes().any { it.name.endsWith(factorySuffix) } }
        val companionFactory = objects(includeNested = false).any { it.hasFunctionWithName(factoryFunctionName) }
        return namedFactory || nestedFactory || companionFactory
    }

    private fun KoClassDeclaration.hasSingleNonDefaultedConstructor(): Boolean =
        constructors.size <= 1 &&
            constructors.none { constructor -> constructor.hasParameter { parameter -> parameter.hasDefaultValue() } }

    private companion object {
        /**
         * Guard against a silently empty/degraded production scope: the module
         * has 17 production `*ViewModel` classes today.
         */
        const val MIN_EXPECTED_VIEW_MODELS = 15
    }
}
