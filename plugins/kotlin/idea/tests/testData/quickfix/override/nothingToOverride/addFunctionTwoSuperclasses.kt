// "Add function to supertype…" "true"
open class A {
}
open class B : A() {
}
class C : B() {
    <caret>override fun f() {}
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddFunctionToSupertypeFix
// IGNORE_K2