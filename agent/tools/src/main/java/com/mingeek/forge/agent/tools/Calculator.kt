package com.mingeek.forge.agent.tools

/**
 * Tiny expression evaluator: + - * / with parentheses, integer + floating literals,
 * unary minus. Uses the shunting-yard algorithm. No external deps.
 */
internal object Calculator {

    fun evaluate(expression: String): Double {
        val tokens = tokenize(expression)
        val rpn = shuntingYard(tokens)
        return evalRpn(rpn)
    }

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Op(val symbol: Char, val precedence: Int, val rightAssoc: Boolean = false) : Token
        data object LeftParen : Token
        data object RightParen : Token
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var prevWasOperandOrCloseParen = false
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens += Token.Number(expr.substring(start, i).toDouble())
                    prevWasOperandOrCloseParen = true
                }
                c == '(' -> {
                    tokens += Token.LeftParen
                    prevWasOperandOrCloseParen = false
                    i++
                }
                c == ')' -> {
                    tokens += Token.RightParen
                    prevWasOperandOrCloseParen = true
                    i++
                }
                c in "+-*/" -> {
                    if (c == '-' && !prevWasOperandOrCloseParen) {
                        // unary minus → emit 0 then '-'
                        tokens += Token.Number(0.0)
                    }
                    tokens += when (c) {
                        '+', '-' -> Token.Op(c, precedence = 1)
                        '*', '/' -> Token.Op(c, precedence = 2)
                        else -> error("unreachable")
                    }
                    prevWasOperandOrCloseParen = false
                    i++
                }
                else -> error("Unexpected character '$c' in expression")
            }
        }
        return tokens
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        val ops = ArrayDeque<Token>()
        for (t in tokens) when (t) {
            is Token.Number -> out += t
            is Token.LeftParen -> ops.addLast(t)
            is Token.RightParen -> {
                while (ops.isNotEmpty() && ops.last() !is Token.LeftParen) {
                    out += ops.removeLast()
                }
                check(ops.isNotEmpty()) { "Mismatched parentheses" }
                ops.removeLast() // pop the '('
            }
            is Token.Op -> {
                while (ops.isNotEmpty()) {
                    val top = ops.last()
                    if (top is Token.Op &&
                        (top.precedence > t.precedence ||
                            (top.precedence == t.precedence && !t.rightAssoc))
                    ) {
                        out += ops.removeLast()
                    } else break
                }
                ops.addLast(t)
            }
        }
        while (ops.isNotEmpty()) {
            val top = ops.removeLast()
            check(top !is Token.LeftParen) { "Mismatched parentheses" }
            out += top
        }
        return out
    }

    private fun evalRpn(rpn: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (t in rpn) when (t) {
            is Token.Number -> stack.addLast(t.value)
            is Token.Op -> {
                check(stack.size >= 2) { "Malformed expression" }
                val b = stack.removeLast()
                val a = stack.removeLast()
                stack.addLast(
                    when (t.symbol) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> {
                            if (b == 0.0) error("Division by zero")
                            a / b
                        }
                        else -> error("Unknown operator '${t.symbol}'")
                    }
                )
            }
            else -> error("Unexpected token in RPN: $t")
        }
        check(stack.size == 1) { "Malformed expression" }
        return stack.last()
    }
}
