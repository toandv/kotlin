// !WITH_NEW_INFERENCE
fun foo(arg: Int?): Int {
    var i = arg
    if (i != null && i++ == 5) {
        return i-- + i
    }
    return 0
}

operator fun Long?.inc() = this?.let { it + 1 }

fun bar(arg: Long?): Long {
    var i = arg
    if (i++ == 5L) {
        return i<!INAPPLICABLE_CANDIDATE!>--<!> + i
    }
    if (i++ == 7L) {
        return i++ <!INAPPLICABLE_CANDIDATE!>+<!> i
    }
    return 0L
}