package com.dichotome.klox.parser

import com.dichotome.klox.Lox
import com.dichotome.klox.grammar.Expr
import com.dichotome.klox.grammar.Stmt
import com.dichotome.klox.scanner.Token
import com.dichotome.klox.scanner.TokenType
import com.dichotome.klox.scanner.TokenType.*


class Parser(
    private val tokens: List<Token>
) {
    private class ParseError : RuntimeException()

    companion object {
        fun error(token: Token, message: String) = Lox.report(
            token.line,
            " at ${if (token.type === EOF) "end" else "'" + token.lexeme + "'"}",
            message
        )
    }

    private var current = 0

    fun parse(): List<Stmt> = arrayListOf<Stmt>().apply {
        while (!isAtEnd()) {
            declaration()?.let { add(it) }
        }
    }

    private fun block(): Stmt {
        val statements = arrayListOf<Stmt>()

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            declaration()?.let { statements += it }
        }

        consume(RIGHT_BRACE, "Expect } at the end of block")

        return Stmt.Block(statements)
    }

    private fun declaration(): Stmt? = try {
        when {
            match(VAR) -> varDeclaration()
            else -> assignment()
        }.also {
            match(NEW_LINE)
        }
    } catch (e: ParseError) {
        synchronize()
        null
    }

    private fun varDeclaration(): Stmt {
        if (peek().type != IDENTIFIER) {
            return statement()
        }

        if (next().type != EQUAL) {
            return Stmt.Var(consume(IDENTIFIER, ""), null)
        }

        val assignment = assignment()

        if (assignment !is Stmt.Assign) {
            return statement()
        }

        return Stmt.Var(assignment.name, assignment)
    }

    private fun assignment(): Stmt {
        if (peek().type == IDENTIFIER && next().type == EQUAL) {
            val target = consume(IDENTIFIER, "")
            consume(EQUAL, "")
            return Stmt.Assign(
                target,
                if (peek().type == IDENTIFIER && next().type == EQUAL) {
                    assignment()
                } else {
                    Stmt.Expression(expression())
                }
            )
        }
        return statement()
    }

    private fun whileStatement(): Stmt {
        consume(LEFT_PAREN, "Expect '(' after 'while'")
        val condition = expression()
        consume(RIGHT_PAREN, "Expect ')' after 'while'")
        val body = statement()

        return Stmt.While(condition, body)
    }

    private fun forStatement(): Stmt {
        consume(LEFT_PAREN, "Expect '(' after 'for'")

        var initializer = when {
            match(SEMICOLON) -> null
            match(VAR) -> varDeclaration()
            else -> expressionStatement()
        }
        consume(SEMICOLON, "Expect ';' after 'for' condition")


        var condition: Expr? = null
        if (!check(SEMICOLON)) {
            condition = expression()
        }
        consume(SEMICOLON, "Expect ';' after 'for' loop condition")

        var increment: Stmt? = null
        if (!check(RIGHT_PAREN)) {
            increment = assignment()
        }
        consume(RIGHT_PAREN, "Expect ')' after 'for'")

        val body = statement()

        return Stmt.For(initializer, condition, increment, body)
    }

    private fun ifStatement(): Stmt {
        consume(LEFT_PAREN, "Expect '(' after 'if'")
        val condition = expression()
        consume(RIGHT_PAREN, "Expect ')' after 'if'")

        val thenBranch = statement()
        val elseBranch = if (match(ELSE)) statement() else null

        return Stmt.If(condition, thenBranch, elseBranch)
    }

    private fun print(): Stmt = Stmt.Print(expression())

    private fun expressionStatement(): Stmt = Stmt.Expression(expression())

    private fun statement(): Stmt = when {
        match(CONTINUE) -> Stmt.Continue(previous())
        match(BREAK) -> Stmt.Break(previous())
        match(FOR) -> forStatement()
        match(WHILE) -> whileStatement()
        match(IF) -> ifStatement()
        match(LEFT_BRACE) -> block()
        match(PRINT) -> print()
        else -> expressionStatement()
    }

    private fun expression(): Expr = comma()

    private fun comma(): Expr {
        val list = arrayListOf(ternary())
        while (match(COMMA)) {
            list += ternary()
        }
        return if (list.size == 1) list.first() else Expr.Comma(list)
    }

    private fun ternary(): Expr {
        var expr = or()
        val second: Expr
        val third: Expr
        val firstToken = peek()
        if (match(QUESTION)) {
            try {
                second = ternary()
                if (match(COLON)) {
                    third = ternary()
                    expr = Expr.Ternary(firstToken, expr, second, third)
                } else {
                    throw error(
                        peek(),
                        "Incomplete ternary operator: '$expr ? $second'"
                    )
                }
            } catch (e: ParseError) {
                throw error(
                    previous(),
                    "Incomplete ternary operator: '$expr ?'\nCause '?' and ':' branches are missing"
                )
            }
        }
        return expr
    }

    private fun or(): Expr {
        var expr = and()

        while (match(OR)) {
            val operator = previous()
            val right = and()
            expr = Expr.Logical(expr, operator, right)
        }

        return expr
    }

    private fun and(): Expr {
        var expr = equality()

        while (match(AND)) {
            val operator = previous()
            val right = equality()
            expr = Expr.Logical(expr, operator, right)
        }

        return expr
    }

    private fun equality(): Expr {
        var expr = comparison()
        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
            val operator = previous()
            val right = comparison()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun comparison(): Expr {
        var expr = addition()
        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            val operator = previous()
            val right = addition()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun addition(): Expr {
        var expr = multiplication()
        while (match(MINUS, PLUS)) {
            val operator = previous()
            val right = multiplication()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun multiplication(): Expr {
        var expr = unary()
        while (match(SLASH, STAR, HAT, MOD)) {
            val operator = previous()
            val right = unary()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun unary(): Expr {
        if (match(BANG, MINUS)) {
            val operator = previous()
            val right = unary()
            return Expr.Unary(operator, right)
        }
        return primary()
    }

    private fun primary(): Expr =
        when {
            match(FALSE) -> Expr.Literal(false)
            match(TRUE) -> Expr.Literal(true)
            match(NIL) -> Expr.Literal(null)
            match(NUMBER, STRING) -> Expr.Literal(previous().literal)
            match(IDENTIFIER) -> Expr.Variable(previous())
            match(LEFT_PAREN) -> {
                val expr = expression()
                consume(RIGHT_PAREN, "Expect ')' after expression.")
                Expr.Grouping(expr)
            }
            match(NEW_LINE) -> Expr.None()
            checkBinaryOperator(peek()) ->
                throw noLeftOperandError(peek())
            else -> throw error(peek(), "Expect expression. ${previous()}, ${peek()}")
        }

    private fun checkBinaryOperator(token: Token) =
        token.type in listOf(
            COMMA,
            QUESTION,
            BANG_EQUAL,
            EQUAL_EQUAL,
            GREATER,
            GREATER_EQUAL,
            LESS,
            LESS_EQUAL,
            MINUS,
            PLUS,
            SLASH,
            STAR,
            BANG,
            MINUS,
            MOD
        )

    private fun match(vararg types: TokenType): Boolean {
        types.forEach {
            if (check(it)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun consumeLineEnd(): Token =
        consume(listOf(NEW_LINE, EOF), "Expect new line after expression ${peek().lexeme}")

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(peek(), message)
    }

    private fun consume(types: List<TokenType>, message: String): Token {
        if (check(types)) return advance()
        throw error(peek(), message)
    }

    private fun noLeftOperandError(token: Token): ParseError =
        error(token, "Missing left operand before ${token.lexeme}")

    private fun error(token: Token, message: String): ParseError {
        Parser.error(token, message)
        return ParseError()
    }

    private fun check(types: List<TokenType>): Boolean =
        types.fold(false) { acc, tokenType -> acc || check(tokenType) }

    private fun check(type: TokenType): Boolean =
        if (isAtEnd() && type != EOF) {
            false
        } else {
            peek().type == type
        }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean =
        peek().type === EOF

    private fun next(): Token =
        tokens[current + 1]

    private fun peek(): Token =
        tokens[current]

    private fun previous(): Token =
        tokens[current - 1]

    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type === SEMICOLON) return
            when (peek().type) {
                CLASS, FUN, VAR, FOR, IF, WHILE, PRINT, RETURN -> return
                else -> advance()
            }
        }
    }
}