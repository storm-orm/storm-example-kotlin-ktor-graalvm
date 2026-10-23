package st.orm.demo.imdb

import st.orm.demo.imdb.model.User
import st.orm.demo.imdb.repository.UserRepository
import st.orm.repository.repository
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import java.time.Instant

/** Prints every captured statement so the test output shows the generated SQL. */
fun SqlCapture.printStatements(label: String) {
    statements().forEach { captured ->
        println("[$label] ${captured.operation()}: ${captured.statement().replace('\n', ' ')}")
    }
}

/** An account with a row, as the first save stores it; anonymous unless given a name. */
fun ORMTemplate.insertUser(id: String, name: String? = null): User {
    val now = Instant.parse("2026-07-01T09:00:00Z")
    return repository<UserRepository>().insertAndFetch(User(id = id, name = name, avatarUrl = null, createdAt = now, lastSeenAt = now))
}
