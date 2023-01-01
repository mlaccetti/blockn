package rckt.blockn

fun formatTag(_tag: String): String {
  return if (_tag.startsWith("rckt.blockn")) {
    _tag
  } else {
    // In some cases (e.g. coroutines) our code runs in the context of
    // other classes - make sure we prefix it for findability regardless.
    "rckt.blockn ($_tag)"
  }
}

val Any.TAG: String
  get() {
    return formatTag(javaClass.name)
  }
