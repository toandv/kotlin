// !LANGUAGE: +NewInference
// !DIAGNOSTICS: -UNUSED_PARAMETER

fun /*<K> */take(fn: () -> List<String>) {}
fun <T> materialize(): T = TODO()

fun test() {
    take(fun () = materialize())
    take { materialize() }
}
