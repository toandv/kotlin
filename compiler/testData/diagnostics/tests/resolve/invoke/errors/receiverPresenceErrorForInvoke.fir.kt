fun test1(f: String.() -> Unit) {
    (<!UNRESOLVED_REFERENCE!>f<!>)()

    <!UNRESOLVED_REFERENCE!>f<!>()
}

fun test2(f: (Int) -> Int) {
    1.<!UNRESOLVED_REFERENCE!>f<!>(2)

    2.(<!UNRESOLVED_REFERENCE!>f<!>)(2)
}