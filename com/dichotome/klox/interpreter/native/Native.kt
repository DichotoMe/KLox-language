package com.dichotome.klox.interpreter.native

import com.dichotome.klox.interpreter.Interpreter
import com.dichotome.klox.interpreter.callable.LoxCallable

object Native {
    private val functions = arrayListOf<LoxCallable>()

    init {
        clock()
        print()
        println()
    }

    fun defineAll(interpreter: Interpreter) {
        functions.forEach {
            interpreter.globals.apply {
                define(it.name, it)
            }
        }
    }

    private fun createLoxCallable(
        name: String,
        arity: Int,
        call: (interpreter: Interpreter, arguments: List<Any>) -> Any
    ) = object : LoxCallable {
        override val name: String = name

        override val arity: Int = arity

        override fun call(interpreter: Interpreter, arguments: List<Any>): Any =
            call(interpreter, arguments)

        override fun toString(): String = "<native $name>"
    }.also {
        functions += it
    }

    private fun clock() = createLoxCallable("clock", 0) { _, _ ->
        System.currentTimeMillis() / 1_000
    }

    private fun print() = createLoxCallable("print", 1) { _, args ->
        print(args.first().stringify())
    }

    private fun println() = createLoxCallable("println", 1) { _, args ->
        println(args.first().stringify())
    }
}