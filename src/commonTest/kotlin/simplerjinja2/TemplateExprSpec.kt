package simplerjinja2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateExprSpec {
    @Test
    fun parses_use_and_for_block() {
        val expr = TemplateExpr.fromText("Hello {{ user.name }}{% for item in items %}- {{ item }}{% endfor %}")
        val concat = expr as TemplateExpr.Concat
        assertTrue(concat.exprs.any { it is TemplateExpr.Use })
        assertTrue(concat.exprs.any { it is TemplateExpr.For })
    }

    @Test
    fun executes_simple_template() {
        val expr = TemplateExpr.fromText("Hello {{ user.name }}!")
        val ctx = mapOf("user" to TmplValue.Map(mapOf("name" to TmplValue.String("Wabbit"))))
        assertEquals("Hello Wabbit!", expr.execute(ctx))
    }
}
