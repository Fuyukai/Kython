"""
kyc file creator. Used to compile py files into kyc files, as a replacement for the internal
pyc format for Kython.

This is used to implement compile() using CPython as a compiler backend from within Kython,
but can be used on any interpreter to generate compliant kyc files.
"""
from __future__ import annotations

# This file is only allowed to import the sys and os modules, because potentially it can be used
# with a Python implementation that has no modules available. Sys/os will always be available.
# sys is only used for path parsing, or in command mode, std i/o.
# os is only used for working directory.
import sys

# kyc is a simple format very very similar to marshal, except documented, no refs, and with less
# redundant types.
# see SPEC.rst for more info.

# type annotations; these are ok because they're never really imported.
if False:
    import types
    from typing import Tuple, Any, List, Dict

try:
    sys.getwindowsversion()
except AttributeError:
    path_sep = "/"
else:
    path_sep = "\\"

CODE_COMMENT = "This is an internal file generated by Kython. Do not edit!"


def _compile_int(i: int) -> bytes:
    """
    Compiles an int into bytes.
    """
    # TODO: OverflowError
    return b"i" + i.to_bytes(length=4, byteorder="little", signed=True)


def _compile_bytestring(s: bytes) -> bytes:
    """
    Compiles a bytestring into specific bytes.
    """
    size = len(s).to_bytes(length=4, byteorder="little")
    return b"b" + size + s


def _compile_unicode_string(s: str) -> bytes:
    """
    Compiles a unicode string into bytes.
    """
    encoded = s.encode("utf-8")
    size = len(encoded).to_bytes(length=4, byteorder="little")
    return b"s" + size + encoded


def _compile_tuple(t: Tuple[Any]) -> bytes:
    """
    Compiles a tuple into bytes.
    """
    size = len(t).to_bytes(length=4, byteorder="little")
    items = []
    for item in t:
        items.append(_compile_object(item))

    return b"t" + size + b"".join(items)


def _compile_list(t: List[Any]) -> bytes:
    """
    Compiles a list into bytes.
    """
    size = len(t).to_bytes(length=4, byteorder="little")
    items = []
    for item in t:
        items.append(_compile_object(item))

    return b"l" + size + b"".join(items)


def _compile_dict(t: Dict[Any, Any]) -> bytes:
    """
    Compiles a dict into bytes.
    """
    size = len(t).to_bytes(length=4, byteorder="little")
    items = []
    for k, v in t.items():
        items.append(_compile_object(k))
        items.append(_compile_object(v))

    return b"d" + size + b"".join(items)


def _compile_nonetype() -> bytes:
    """
    Compiles a none.
    """
    return b"N"


def _compile_bool(v: bool) -> bytes:
    return [b"-", b"+"][v]


def _compile_float(v: float) -> bytes:
    """
    Compiles a float.
    """
    # temporary!
    import struct
    return b"f" + struct.pack("<d", v)


def _compile_code_object(obb: types.CodeType) -> bytes:
    """
    Compiles a code object into bytes.
    """
    items = [
        _compile_int(obb.co_argcount),
        _compile_int(obb.co_posonlyargcount),
        _compile_int(obb.co_kwonlyargcount),
        _compile_int(obb.co_nlocals),
        _compile_int(obb.co_stacksize),
        _compile_int(obb.co_flags),

        _compile_bytestring(obb.co_code),
        _compile_tuple(obb.co_consts),
        _compile_tuple(obb.co_names),
        _compile_tuple(obb.co_varnames),
        _compile_tuple(obb.co_freevars),
        _compile_tuple(obb.co_cellvars),

        _compile_unicode_string(obb.co_filename),
        _compile_unicode_string(obb.co_name),
        _compile_int(obb.co_firstlineno),
        _compile_bytestring(obb.co_lnotab)
    ]
    return b"c" + b"".join(items)


def _compile_object(i: Any) -> bytes:
    """
    Compiles any object into bytes.
    """
    if isinstance(i, int):
        return _compile_int(i)
    elif isinstance(i, bytes):
        return _compile_bytestring(i)
    elif isinstance(i, str):
        return _compile_unicode_string(i)
    elif isinstance(i, tuple):
        return _compile_tuple(i)
    elif isinstance(i, list):
        return _compile_list(i)
    elif isinstance(i, dict):
        return _compile_dict(i)
    elif isinstance(i, bool):
        return _compile_bool(i)
    elif isinstance(i, float):
        return _compile_float(i)
    elif hasattr(i, "co_code"):
        return _compile_code_object(i)
    elif i is None:
        return _compile_nonetype()

    raise ValueError(f"Unknown type {type(i)}")


def compile_kyc(
    path: str, ending: str = ".py", *, full_pathname: bool = False
) -> bytes:
    """
    Compiles a file to kyc.

    :param path: The full path of the file to compile into a code object.
    :return: The byte-encoded object.
    """
    last = path.split(path_sep)[-1]
    first = path_sep.join(path.split(path_sep)[:-1])
    if not first:
        first = "."

    filename = ending.join(last.split(ending)[:-1])
    kyc_name = filename + ".kyc"

    with open(path, "r") as f:
        file_data = f.read()

    if full_pathname:
        compiled_filename = path
    else:
        compiled_filename = last
    compiled = compile(file_data, compiled_filename, "exec")
    code_object = _compile_code_object(compiled)

    # Items:
    #  - Magic number (3 bytes): KYC
    #  - Version signifier (1 byte): A, for version 1 of KYC
    #  - PyVersion (1 byte): Minor version of Python implementation (3.8 -> 8)
    #  - kyc type marker: 'K'
    #  - Long type marker: 'L'
    #  - Long: Hash of source code file
    #  - Unicode string type marker: 's'
    #  - String: File comment
    #  - Code object type ('c')
    #  - Code object: The module object
    items = [
        b"KYCA",
        (sys.version_info[1]).to_bytes(length=1, byteorder="little"),
        b"K",
        b"L",
        b"\x00\x00\x00\x00\x00\x00\x00\x00",
        _compile_unicode_string(CODE_COMMENT),
        code_object,
    ]

    b = b"".join(items)

    with open(first + path_sep + kyc_name, "wb") as f:
        f.write(b)

    return b


def daemonise():
    """
    Runs the compiler as a daemon.
    """
    # we communicate through stdin/stdout
    # all messages are delimited by space
    # commands:
    #  - i (s2c): info message to the client, showing our capabilities
    #  - r (s2c): daemon ready
    #  - c (c2s): compile code at <path>
    #  - C (s2c): compiled code output, hexadecimal encoded
    #  - u (s2c): unknown command
    #  - e (s2c): python error, hexadecimal encoded
    #  - # (s2c): debug output
    v = sys.version_info
    ver_string = f"{v[0]}.{v[1]}.{v[2]}-{v.releaselevel}"

    def send_info():
        print(f"i py-kyc {ver_string} {sys.implementation.name}")

    send_info()
    print(f"# kyc will be compiled with version 1")
    print(f"r")

    while True:
        data = sys.stdin.readline()
        command, *args = data.split()
        if command == "c":
            try:
                compiled = compile_kyc(args[0])
            except SyntaxError as e:
                # todo...
                print(f"e {str(e).encode('utf-8').hex()}")
            except Exception as e:
                print(f"e {str(e).encode('utf-8').hex()}")
            else:
                print(f"C {compiled.hex()}")

        else:
            print(f"u")


def main(daemon: bool):
    if daemon:
        daemonise()

    else:
        filename = sys.argv[1]
        output = compile_kyc(filename)
        print(output.hex())


if __name__ == "__main__":
    if sys.argv[1] == "--daemon":
        main(True)
    else:
        main(False)
