class C

class B

class A {
    val B.foo: C.() -> Unit get() = null
}

fun <T, R> with(arg: T, f: T.() -> R): R = arg.f()

fun test(a: A, b: B, c: C) {
    with(a) {
        with(c) {
            b.foo(c)
        }
        with(b) {
            c.<!UNRESOLVED_REFERENCE!>foo<!>()
            with(c) {
                foo()
            }
        }
    }
}
