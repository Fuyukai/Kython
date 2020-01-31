/*
 * This file is part of kython.
 *
 * kython is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * kython is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with kython.  If not, see <https://www.gnu.org/licenses/>.
 */

package green.sailor.kython.interpreter.pyobject.function

import green.sailor.kython.interpreter.Builtins
import green.sailor.kython.interpreter.Exceptions
import green.sailor.kython.interpreter.callable.ArgType
import green.sailor.kython.interpreter.callable.PyCallable
import green.sailor.kython.interpreter.callable.PyCallableSignature
import green.sailor.kython.interpreter.instruction.Instruction
import green.sailor.kython.interpreter.kyobject.KyCodeObject
import green.sailor.kython.interpreter.kyobject.KyUserModule
import green.sailor.kython.interpreter.pyobject.*
import green.sailor.kython.interpreter.pyobject.generator.PyGenerator
import green.sailor.kython.interpreter.pyobject.internal.PyCellObject
import green.sailor.kython.interpreter.pyobject.internal.PyCodeObject
import green.sailor.kython.interpreter.stack.StackFrame
import green.sailor.kython.interpreter.stack.UserCodeStackFrame
import green.sailor.kython.interpreter.throwKy
import green.sailor.kython.interpreter.typeError

/**
 * Represents a Python function object.
 *
 * @param codeObject: The marshalled code object to transform into a real code object.
 */
class PyUserFunction(
    codeObject: KyCodeObject,
    val defaults: Map<String, PyObject> = mapOf(),
    val defaultsTuple: List<PyObject> = listOf(),
    closure: List<PyCellObject>? = null
) : PyFunction(codeObject.codename) {
    object PyUserFunctionType : PyType("function") {
        override fun newInstance(kwargs: Map<String, PyObject>): PyObject {
            val code = kwargs["code"] ?: error("Built-in signature mismatch")
            if (code !is PyCodeObject) {
                typeError("Arg 'code' is not a code object")
            }
            return PyUserFunction(code.wrappedCodeObject)
        }

        override val signature: PyCallableSignature by lazy {
            PyCallableSignature(
                "code" to ArgType.POSITIONAL,
                "globals" to ArgType.POSITIONAL
            )
        }
    }

    /** The code object for this function. */
    val code = codeObject

    /** If this function is a generator. Alias for code.flags.isGenerator */
    val isGenerator: Boolean get() = code.flags.isGenerator

    /** If this function is async. Alias for code.flags.isAsync */
    val isAsync: Boolean get() = code.flags.isAsync

    /** The closure for this function. */
    val closure = closure?.let {
        Array(closure.size) { closure[it] }
    } ?: emptyArray()

    /**
     * The PyCodeObject for this function.
     * Used to expose the code object to Python land.
     */
    val wrappedCode = PyCodeObject(code)

    /** The KyModule for this function. */
    lateinit var module: KyUserModule

    // helper methods
    /**
     * Gets the instruction at the specified index.
     */
    fun getInstruction(idx: Int): Instruction = code.instructions[idx]

    /**
     * Gets a global from the globals for this function.
     */
    fun getGlobal(name: String): PyObject {
        return module.attribs[name]
            ?: Builtins.BUILTINS_MAP[name]
            ?: Exceptions.NAME_ERROR("Name $name is not defined").throwKy()
    }

    override fun createFrame(): StackFrame = UserCodeStackFrame(this)

    override fun pyToStr(): PyString {
        val hashCode = System.identityHashCode(this).toString(16)
        return PyString("<user function '$name' at 0x$hashCode>")
    }
    override fun pyGetRepr(): PyString = pyToStr()

    override val internalDict: MutableMap<String, PyObject> = super.internalDict.apply {
        put("__code__", wrappedCode)
    }

    override fun kyCall(args: List<PyObject>): PyObject {
        if (code.flags.isGenerator) {
            val sig = kyGetSignature()
            val transformed = sig.argsToKwargs(args)
            val us = this as PyCallable
            val frame = us.createFrame() as UserCodeStackFrame
            frame.setupLocals(transformed)
            return PyGenerator(frame)
        }
        return super.kyCall(args)
    }

    override fun pyCall(args: List<PyObject>, kwargTuple: List<String>): PyObject {
        if (code.flags.isGenerator) {
            val sig = kyGetSignature()
            val transformed = sig.callFunctionGetArgs(args, kwargTuple)
            val us = this as PyCallable
            val frame = us.createFrame() as UserCodeStackFrame
            frame.setupLocals(transformed)
            return PyGenerator(frame)
        }
        return super.pyCall(args, kwargTuple)
    }

    override var type: PyType
        get() = PyUserFunctionType
        set(_) = Exceptions.invalidClassSet(this)

    /**
     * Generates a [PyCallableSignature] for this function.
     */
    fun generateSignature(): PyCallableSignature {
        // ref: inspect._signature_from_function
        // varnames starting format: args, args with defaults, *args, kwonly, **kwargs

        // add args
        val args = mutableListOf<Pair<String, ArgType>>()
        for (x in 0 until code.argCount) {
            val name = code.varnames[x]
            args.add(Pair(name, ArgType.POSITIONAL))
        }

        // add a *args if we have one
        if (code.flags.CO_HAS_VARARGS) {
            val name = code.varnames[code.argCount]
            args.add(Pair(name, ArgType.POSITIONAL_STAR))
        }

        // keyword only
        for (x in 0 until code.kwOnlyArgCount) {
            val offset = code.argCount + x
            val name = code.varnames[offset]
            args.add(Pair(name, ArgType.KEYWORD))
        }

        // **kwargs
        if (code.flags.CO_HAS_VARKWARGS) {
            val name = code.varnames[code.argCount + code.kwOnlyArgCount]
            args.add(Pair(name, ArgType.KEYWORD_STAR))
        }

        val sig = PyCallableSignature(*args.toTypedArray())
        sig.defaults.putAll(defaults)
        return sig
    }

    override val signature: PyCallableSignature by lazy { generateSignature() }
}
