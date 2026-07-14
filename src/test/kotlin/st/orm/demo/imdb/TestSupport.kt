package st.orm.demo.imdb

import st.orm.test.SqlCapture

/** Prints every captured statement so the test output shows the generated SQL. */
fun SqlCapture.printStatements(label: String) {
    statements().forEach { captured ->
        println("[$label] ${captured.operation()}: ${captured.statement().replace('\n', ' ')}")
    }
}
