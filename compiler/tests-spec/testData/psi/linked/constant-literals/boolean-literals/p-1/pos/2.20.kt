/*
 * KOTLIN PSI SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-draft
 * PLACE: constant-literals, boolean-literals -> paragraph 1 -> sentence 2
 * NUMBER: 20
 * DESCRIPTION: The use of Boolean literals as the identifier (with backtick) in the infixFunctionCall.
 * NOTE: this test data is generated by FeatureInteractionTestDataGenerator. DO NOT MODIFY CODE MANUALLY!
 */

fun f() {
    1 + 1 `true` 10..11

    1 + 1 `false` 10..11 `true` 10 `false` -.9-9.0
}