package rag.code

/** What a language family's rules say a block's header is (see [FamilyRules.classify]). */
sealed interface Classified {
    /** A class-like declaration: class, interface, object, struct, enum, trait, protocol, `impl`, `extension`. */
    data class Class(val name: String) : Classified

    data class Function(val name: String) : Classified

    /** A wrapper that is not a level of its own (`namespace`, `mod`): its contents count as if it weren't there. */
    data object Transparent : Classified
}

/**
 * The language-specific part of [DeclarationFinder] (SPEC §18.4): everything else is shared.
 * All input is masked text (comments and literals are spaces) with whitespace collapsed to single
 * spaces and trimmed.
 */
internal interface FamilyRules {
    /**
     * What the block whose header is [header] declares, or null when it is anything else (`if`, `for`,
     * `try`, a lambda, an `init {}` block, an object literal…). [header] is the signature in front of
     * the block's `{`, without the `{`, e.g. `@Test fun parses(a: Int): Boolean`.
     */
    fun classify(header: String): Classified?

    /**
     * The name of the function declared by the statement [statement] when that function has no brace
     * body but does have code (`fun area() = w * h`, `int Size() => items.Count;`), else null.
     * A declaration without any body (abstract, interface or trait signature, C prototype) is not one.
     */
    fun bodylessFunction(statement: String): String?

    /** Line prefixes of annotations and attributes that belong to the declaration below them: `@`, `#[`, `[`. */
    val annotationPrefixes: List<String>
}

/** The rules of each family; null until the family's step adds them (plan steps 2b–2f). */
internal fun rulesFor(language: Language): FamilyRules? = when (language) {
    Language.KOTLIN -> KotlinRules
    Language.JAVA -> JavaRules
    Language.CSHARP -> CSharpRules
    Language.JS_TS -> JsRules
    Language.GO -> GoRules
    Language.RUST -> RustRules
    Language.SWIFT -> SwiftRules
    Language.C_CPP -> CppRules
    else -> null
}
