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

package green.sailor.kython.interpreter.pyobject.iterators

import green.sailor.kython.interpreter.Exceptions
import green.sailor.kython.interpreter.pyobject.PyObject
import green.sailor.kython.interpreter.pyobject.PyType
import green.sailor.kython.interpreter.pyobject.PyUndicted
import green.sailor.kython.interpreter.pyobject.numeric.PyInt

/**
 * Represents an iterator over a range object.
 */
class PyRangeIterator(val range: PyRange) : PyUndicted {
    override var type: PyType
        get() = PyBuiltinIterator.PyGenericIteratorType
        set(_) = Exceptions.invalidClassSet(this)

    // used for iterating
    var currentStep: Long = 0

    override fun pyNext(): PyObject {
        val next = range.start.wrappedInt + (currentStep * range.step.wrappedInt)
        currentStep += 1
        if (next >= range.stop.wrappedInt) {
            Exceptions.STOP_ITERATION().throwKy()
        }
        return PyInt(next)
    }
}
