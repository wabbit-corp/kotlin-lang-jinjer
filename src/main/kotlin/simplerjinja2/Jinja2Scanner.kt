package simplerjinja2

import kotlinx.serialization.Serializable
import one.wabbit.parsing.CharInput
import one.wabbit.parsing.TextAndPosSpan
import kotlin.contracts.ExperimentalContracts

@Serializable
data class Id(val id: String, val span: TextAndPosSpan)
@Serializable
data class LongId(val parts: List<String>, val span: TextAndPosSpan)

@Serializable
sealed class TemplateToken {
    abstract val span: TextAndPosSpan

    @Serializable
    data class EOF(override val span: TextAndPosSpan) : TemplateToken()

    @Serializable
    data class Raw(val text: String, override val span: TextAndPosSpan) : TemplateToken()

    @Serializable
    data class InvalidExpr(val rawText: String, override val span: TextAndPosSpan) : TemplateToken()

    // {{ x.y }}
    @Serializable
    data class Use(val id: LongId, override val span: TextAndPosSpan) : TemplateToken()

    // {% for x in y %}..{% endfor %}
    @Serializable
    data class ForStart(val varName: Id, val inExpr: LongId, override val span: TextAndPosSpan) : TemplateToken()
    @Serializable
    data class ForEnd(override val span: TextAndPosSpan) : TemplateToken()

    // {% if x %}..{% elif y %}..{% else %}..{% endif %}
    @Serializable
    data class If(val expr: LongId, override val span: TextAndPosSpan) : TemplateToken()
    @Serializable
    data class Elif(val expr: LongId, override val span: TextAndPosSpan) : TemplateToken()
    @Serializable
    data class Else(override val span: TextAndPosSpan) : TemplateToken()
    @Serializable
    data class IfEnd(override val span: TextAndPosSpan) : TemplateToken()

    fun normalizedRawText(): String {
        return when (this) {
            is EOF         -> ""
            is Raw         -> text
            is InvalidExpr -> rawText
            is Use         -> "{{ ${id.parts.joinToString(".")} }}"
            is ForStart    -> "{% for ${varName.id} in ${inExpr.parts.joinToString(".")} %}"
            is ForEnd      -> "{% endfor %}"
            is If          -> "{% if ${expr.parts.joinToString(".")} %}"
            is Elif        -> "{% elif ${expr.parts.joinToString(".")} %}"
            is Else        -> "{% else %}"
            is IfEnd       -> "{% endif %}"
        }
    }
}

enum class Keyword {
    FOR, IF, ELIF, ELSE, ENDIF, ENDFOR, IN
}

private const val DEBUG = false

class TemplateScanner(val input: CharInput<TextAndPosSpan>) {
    var current: TemplateToken = readToken()

    fun advance() {
        current = readToken()
    }

    private var debugDepth = 0

    @OptIn(ExperimentalContracts::class)
    private inline fun <R> debug(name: String, f: () -> R): R {
//        contract {
//            callsInPlace(f, InvocationKind.EXACTLY_ONCE)
//        }
        if (!DEBUG) return f()
        val prefix = "  ".repeat(debugDepth)
        println("${prefix}[$name] at ${input}")
        debugDepth++
        val r: R
        try {
            r = f()
        } catch (e: Throwable) {
            println("${prefix}ERROR: $e")
            throw e
        } finally {
            debugDepth--
        }
        println("$prefix=> ${r.toString().replace('\n', ' ').take(30)}")
        return r
    }

    fun scanTokens(): List<TemplateToken> {
        val result = mutableListOf<TemplateToken>()
        while (true) {
            val token = readToken()
            result.add(token)
            if (token is TemplateToken.EOF) break
        }
        return result
    }

    fun readKeyword(): Keyword? = debug("readKW") {
        val start = input.mark()
        while (input.current.isLetter()) input.advance()
        val span = input.capture(start)
        val text = span.raw
        return@debug when (text) {
            "for" -> Keyword.FOR
            "if" -> Keyword.IF
            "elif" -> Keyword.ELIF
            "else" -> Keyword.ELSE
            "endif" -> Keyword.ENDIF
            "endfor" -> Keyword.ENDFOR
            "in" -> Keyword.IN
            else -> return@debug null
        }
    }

    fun readRaw(start: CharInput.Mark): TemplateToken = debug("readRaw") {
        while (input.current != '{' && input.current != CharInput.EOB) input.advance()
        val span = input.capture(start)
        return@debug TemplateToken.Raw(span.raw, span)
    }

    fun invalidExpr(start: CharInput.Mark): TemplateToken = debug("readRaw") {
        val span = input.capture(start)
        return@debug TemplateToken.InvalidExpr(span.raw, span)
    }

    fun skipWhitespace() {
        while (input.current.isWhitespace()) input.advance()
    }

    fun readToken(): TemplateToken = debug("readToken") {
        val tokenStart = input.mark()

        when (input.current) {
            CharInput.EOB -> TemplateToken.EOF(input.capture(tokenStart))
            '{' -> {
                input.advance()
                when (input.current) {
                    CharInput.EOB -> return@debug readRaw(tokenStart)
                    '{' -> {
                        input.advance()

                        skipWhitespace()

                        val longId = readLongId() ?: return@debug readRaw(tokenStart)

                        skipWhitespace()

                        if (input.current != '}')
                            return@debug readRaw(tokenStart)
                        input.advance()

                        if (input.current != '}')
                            return@debug readRaw(tokenStart)
                        input.advance()

                        val span = input.capture(tokenStart)
                        return@debug TemplateToken.Use(longId, span)
                    }
                    '%' -> {
                        input.advance()

                        skipWhitespace()

                        val kw = readKeyword() ?: return@debug invalidExpr(tokenStart)

                        when (kw) {
                            Keyword.FOR -> {
                                skipWhitespace()

                                val varName = readId() ?: return@debug invalidExpr(tokenStart)

                                skipWhitespace()

                                val kw = readKeyword() ?: return@debug invalidExpr(tokenStart)
                                if (kw != Keyword.IN) return@debug invalidExpr(tokenStart)

                                skipWhitespace()

                                val inExpr = readLongId() ?: return@debug invalidExpr(tokenStart)

                                skipWhitespace()

                                if (input.current != '%') return@debug invalidExpr(tokenStart)
                                input.advance()
                                if (input.current != '}') return@debug invalidExpr(tokenStart)
                                input.advance()

                                val span = input.capture(tokenStart)
                                return@debug TemplateToken.ForStart(varName, inExpr, span)
                            }
                            Keyword.ENDFOR -> {
                                skipWhitespace()

                                if (input.current != '%') return@debug invalidExpr(tokenStart)
                                input.advance()
                                if (input.current != '}') return@debug invalidExpr(tokenStart)
                                input.advance()

                                val span = input.capture(tokenStart)
                                return@debug TemplateToken.ForEnd(span)
                            }
                            Keyword.IN -> return@debug invalidExpr(tokenStart)

                            Keyword.IF -> TODO()
                            Keyword.ELIF -> TODO()
                            Keyword.ELSE -> TODO()
                            Keyword.ENDIF -> TODO()
                        }
                    }
                    else -> return@debug readRaw(tokenStart)
                }
            }
            else -> return@debug readRaw(tokenStart)
        }
    }

    fun readId(): Id? = debug("readId") {
        val start = input.mark()

        if (!input.current.isJavaIdentifierStart())
            return@debug null
        input.advance()

        while (input.current.isJavaIdentifierPart())
            input.advance()

        val span = input.capture(start)
        return@debug Id(span.raw, span)
    }

    fun readLongId(): LongId? = debug("readLongId") {
        val start = input.mark()

        val parts = mutableListOf<String>()
        while (true) {
            val id = readId() ?: break
            parts.add(id.id)
            if (input.current != '.') break
            input.advance()
        }

        if (parts.isEmpty()) return@debug null

        val span = input.capture(start)
        return@debug LongId(parts, span)
    }
}

typealias TemplateContext = Map<String, TmplValue>

private fun resolveVar(ctx: TemplateContext, parts: List<String>): TmplValue? {
    var current: TmplValue = TmplValue.Map(ctx)
    for (part in parts) {
        when (current) {
            is TmplValue.Map -> {
                val value = current.value[part] ?: return null
                current = value
            }
            is TmplValue.List -> {
                val index = part.toIntOrNull() ?: return null
                val value = current.value[index] ?: return null
                current = value
            }
            is TmplValue.String -> return null
        }
    }
    return current
}

@Serializable
sealed class TemplateExpr {
    @Serializable data class Raw(val token: TemplateToken.Raw) : TemplateExpr()

    @Serializable data class InvalidExpr(val token: TemplateToken.InvalidExpr) : TemplateExpr()

    // {{ x.y }}
    @Serializable data class Use(val token: TemplateToken.Use) : TemplateExpr()

    // {% for x in y %}..{% endfor %}
    @Serializable data class For(
        val varName: Id, val inExpr: LongId,
        val openToken: TemplateToken.ForStart, val closeToken: TemplateToken,
        val body: TemplateExpr) : TemplateExpr()

    @Serializable data class UnexpectedToken(val token: TemplateToken) : TemplateExpr()

    @Serializable data class Concat(val exprs: List<TemplateExpr>) : TemplateExpr()

    fun isFree(): Boolean = when (this) {
        is Raw -> true
        is InvalidExpr -> true
        is Use -> false
        is For -> false
        is UnexpectedToken -> true
        is Concat -> exprs.all { it.isFree() }
    }

    /**
     * Substitute all variables in the expression with their values. If a variable is not found in the context,
     * it is left as-is.
     */
    fun execute(ctx: TemplateContext): String {
        val result: StringBuilder = StringBuilder()

        fun go(expr: TemplateExpr, ctx: Map<String, TmplValue>) {
            when (expr) {
                is Raw             -> result.append(expr.token.normalizedRawText())
                is InvalidExpr     -> result.append(expr.token.normalizedRawText())
                is UnexpectedToken -> result.append(expr.token.normalizedRawText())
                is Concat          -> for (expr in expr.exprs) go(expr, ctx)

                is Use -> {
                    val id = expr.token.id.parts
                    val value = resolveVar(ctx, id)
                    if (value == null) {
                        // We don't have a value for this expression, so we just
                        // output the raw text.
                        result.append(expr.token.normalizedRawText())
                    }
                    else {
                        when (value) {
                            is TmplValue.String -> result.append(value.value)
                            is TmplValue.Map, is TmplValue.List -> {
                                // Maps and lists can not be used directly in
                                // expressions, so we just output the raw text.
                                result.append(expr.token.normalizedRawText())
                            }
                        }
                    }
                }
                is TemplateExpr.For -> {
                    val list = resolveVar(ctx, expr.inExpr.parts)
                    if (list == null) {
                        // We don't have a value for this expression, so we just
                        // output the raw text.
                        result.append(expr.openToken.normalizedRawText())
                        go(expr.body, ctx)
                        result.append(expr.closeToken.normalizedRawText())
                    }
                    else {
                        when (list) {
                            is TmplValue.List -> {
                                for (item in list.value) {
                                    go(expr.body, ctx + (expr.varName.id to item))
                                }
                            }
                            is TmplValue.Map -> {
                                for ((key, value) in list.value) {
                                    val entry = TmplValue.Map(mapOf(
                                        "key" to TmplValue.String(key),
                                        "value" to value
                                    ))
                                    go(expr.body, ctx + (expr.varName.id to entry))
                                }
                            }
                            is TmplValue.String -> {
                                // Strings can not be used in for expressions, so
                                // we just output the raw text.
                                result.append(expr.openToken.normalizedRawText())
                                go(expr.body, ctx)
                                result.append(expr.closeToken.normalizedRawText())
                            }
                        }
                    }
                }
            }
        }

        go(this, ctx)

        return result.toString()
    }

    /**
     * Substitute variables in this expression, effectively doing a partial evaluation. Invalid expressions
     * are left as-is.
     */
    fun subst(ctx: Map<String, TmplValue>): TemplateExpr {
        fun subst(expr: TemplateExpr, ctx: Map<String, TmplValue>): TemplateExpr {
            return when (expr) {
                is Raw             -> expr
                is InvalidExpr     -> expr
                is UnexpectedToken -> expr
                is Concat          -> Concat(expr.exprs.map { subst(it, ctx) })

                is Use -> {
                    val id = expr.token.id.parts
                    val value = resolveVar(ctx, id)
                    when (value) {
                        null -> expr
                        is TmplValue.String -> Raw(TemplateToken.Raw(value.value, expr.token.span))
                        is TmplValue.Map, is TmplValue.List -> expr
                    }
                }
                is For -> {
                    val id = expr.inExpr.parts
                    val list = resolveVar(ctx, id)
                    when (list) {
                        null -> expr
                        is TmplValue.List -> {
                            val body = mutableListOf<TemplateExpr>()
                            for (item in list.value) {
                                body.add(subst(expr.body, ctx + (expr.varName.id to item)))
                            }
                            Concat(body)
                        }
                        is TmplValue.Map -> {
                            val body = mutableListOf<TemplateExpr>()
                            for ((key, value) in list.value) {
                                val entry = TmplValue.Map(mapOf(
                                    "key" to TmplValue.String(key),
                                    "value" to value
                                ))
                                body.add(subst(expr.body, ctx + (expr.varName.id to entry)))
                            }
                            Concat(body)
                        }
                        is TmplValue.String -> expr
                    }
                }
            }
        }

        return subst(this, ctx)
    }

    companion object {
        fun concat(exprs: List<TemplateExpr>): TemplateExpr {
            require(exprs.isNotEmpty())
            if (exprs.size == 1) return exprs[0]
            return Concat(exprs)
        }
    }
}

class TemplateParser(val input: TemplateScanner) {
    fun read(): TemplateExpr {
        val result = mutableListOf<TemplateExpr>()
        while (true) {
            when (val current = input.current) {
                is TemplateToken.EOF -> return TemplateExpr.concat(result)
                else -> {
                    val body = readExpr()
                    result.addAll(body)
                    if (input.current !is TemplateToken.EOF) {
                        result.add(TemplateExpr.UnexpectedToken(input.current))
                        input.advance()
                    }
                    continue
                }
            }
        }
        return TemplateExpr.concat(result)
    }

    fun readExpr(): List<TemplateExpr> {
        val result = mutableListOf<TemplateExpr>()
        while (true) {
            when (val current = input.current) {
                is TemplateToken.EOF -> return result
                is TemplateToken.Elif, is TemplateToken.Else, is TemplateToken.ForEnd,
                is TemplateToken.IfEnd -> return result
                is TemplateToken.InvalidExpr -> {
                    result.add(TemplateExpr.InvalidExpr(current))
                    input.advance()
                }
                is TemplateToken.Raw -> {
                    result.add(TemplateExpr.Raw(current))
                    input.advance()
                }
                is TemplateToken.Use -> {
                    result.add(TemplateExpr.Use(current))
                    input.advance()
                }

                is TemplateToken.ForStart -> {
                    val forStart = current
                    input.advance()

                    val body = readExpr()
                    val forEnd = input.current
                    input.advance()

                    if (forEnd !is TemplateToken.ForEnd) {
                        result.addAll(body)
                        result.add(TemplateExpr.UnexpectedToken(forEnd))
                        continue
                    }

                    result.add(TemplateExpr.For(forStart.varName, forStart.inExpr, forStart, forEnd, TemplateExpr.concat(body)))
                }
                is TemplateToken.If -> TODO()
            }
        }
        return result
    }
}

sealed class TmplValue {
    data class String(val value: kotlin.String) : TmplValue()
    data class Map(val value: kotlin.collections.Map<kotlin.String, TmplValue>) : TmplValue()
    data class List(val value: kotlin.collections.List<TmplValue>) : TmplValue()
}

fun TemplateExpr.Companion.fromText(text: String): TemplateExpr {
    val scanner = TemplateScanner(CharInput.withTextAndPosSpans(text))
    val parser = TemplateParser(scanner)
    return parser.read()
}
