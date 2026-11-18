package app.gimcheck.core

/** Lee un fixture de `core/src/test/resources`, falla con un mensaje claro si no está. */
internal fun fixture(name: String): String {
    val stream = Fixtures::class.java.getResourceAsStream("/$name")
        ?: error("no está el fixture /$name (¿corriste gen_fixture_web.py?)")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private object Fixtures
