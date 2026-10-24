# Storm Movies · Kotlin + Ktor · GraalVM native image

The [Kotlin + Ktor example](https://github.com/storm-orm/storm-example-kotlin-ktor)
compiled to a GraalVM native image: the same IMDB movie browser with search,
genres, people, statistics, and a watchlist, but the binary starts in about
0.13 seconds, runs the Flyway migration, validates every entity against the
live database schema, and serves pages. No JVM, no warmup.

## How Storm supports this without Spring

Spring applications get Storm's native-image registrations through the Spring
AOT hints in storm-spring. Ktor has no AOT phase, so storm-core ships a
GraalVM feature instead: `StormFeature` reads the compile-time type index the
metamodel processor writes to `META-INF/storm/` and registers, at image build
time, exactly what the Spring hints register:

- every Data type (entities and projections) with constructors, methods, and
  fields, including compound primary keys and inline components reached
  through constructor signatures, and the kotlinx.serialization companions;
- every repository interface as a JDK proxy shape, with the Kotlin
  `$DefaultImpls` classes for default methods;
- converters for reflective construction.

The feature activates automatically through storm-core's
`native-image.properties` whenever the jar is on the image classpath. During
the image build it reports:

```
Storm: registered 15 data types, 0 entity callbacks, 0 converters, and 15 repositories from the type index.
```

The Ktor plugin's repository auto-registration reads the same index at
runtime, so scanned repositories work in the native image with no
configuration at all.

## What the application itself declares

Everything Storm-related comes from the framework. The application-level
metadata in
[`META-INF/native-image/st.orm.demo/storm-imdb-ktor-graalvm/`](src/main/resources/META-INF/native-image/st.orm.demo/storm-imdb-ktor-graalvm/)
covers the rest of the stack:

| Entry | Why |
|---|---|
| `ApplicationKt` reflection | Ktor's `EngineMain` resolves the configured module function reflectively from `application.conf`. |
| View models with their kotlinx companions | Response and template shapes (`SearchSuggestions`, `HomeView`, ...) are serialized through runtime serializer lookup and read by Thymeleaf expressions. |
| Thymeleaf/OGNL-facing JDK types | Template expressions invoke methods reflectively on `#numbers`-style utility objects and plain JDK value and collection types. |
| ktor-network selector fields | The CIO engine initializes atomic field updaters over `InterestSuspensionsMap` and the socket classes. |
| Resources | `templates/`, `static/`, `db/migration/`, `application.conf`, `logback.xml`. |

Two code-level accommodations, both working identically on the JVM:

- **CIO instead of Netty**: Ktor's recommended engine for native images
  (one dependency and the `EngineMain` class in `build.gradle.kts`).
- **Explicit Flyway resources**: native images cannot scan the classpath, so
  the migration hook hands Flyway its migration files through a small
  [`FlywayResources`](src/main/kotlin/st/orm/demo/imdb/FlywayResources.kt)
  provider, the same mechanism Spring Boot uses for its Flyway integration.

## Stack

- Kotlin 2.3 / Java 21, Ktor 3.4 (CIO, Thymeleaf, ContentNegotiation, ktor-server-di)
- Storm ORM (`storm-ktor`) with the KSP metamodel generator and the Storm
  compiler plugin
- **Oracle GraalVM for JDK 23+** (tested with GraalVM for JDK 25)
- PostgreSQL 17 (Docker Compose) with Flyway migrations, run explicitly at startup
- kotlinx.serialization for JSON APIs and cache values; Jackson for parsing
  external APIs
- JUnit 5 + `storm-test` on H2 for repository tests, Playwright for interface tests

## Running the application

Prerequisites: JDK 21, Docker, and a GraalVM for JDK 23+ for the native build.

```bash
# 1. Start PostgreSQL. This is the same Compose stack as the other Storm
#    examples, so a database imported by any of them is shared.
docker compose up -d

# 2. Compile the native image (about 2 minutes)
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile

# 3. Run the binary. On an empty database the first start runs the Flyway
#    migration and imports the IMDB dataset natively (the ~1.3 GB of
#    dataset files are downloaded and cached in ./data); afterwards the
#    data is synced with the dataset at each start and daily (see below).
./build/native/nativeCompile/storm-imdb-ktor-graalvm

# 4. Open the app
open http://localhost:8080
```

The application also still runs on the JVM with `./gradlew run`.

Measured on an Apple M-series MacBook: the binary is a 97 MB self-contained
executable that reports `Application started in 0.129 seconds`, including the
Flyway check and Storm validating all 15 entities against the live schema.

## Keeping the data current

IMDB republishes its dataset daily, and the application syncs with it at
startup, in the background, and every day at `imdb.sync.dailyAt` (03:00 UTC). A dataset file is downloaded again
only when IMDB has published a newer one. The sync
([`ImdbDataSync`](src/main/kotlin/st/orm/demo/imdb/service/ImdbDataSync.kt))
works in chunks of a thousand movies or people, each in its own transaction:
it reads the stored rows, compares them with the dataset as values (entities
are immutable data classes), and writes only the difference, so a day's run
writes the day's changes and an interrupted run is completed by the next.
The initial import on an empty database is the same sync.
A sync records the movies it adds (`movie_addition`, V3), and the Recently
Added page scrolls those, newest day first; the initial import is no
addition, so the page lists what has joined the catalogue since. A sync adds
older movies too, once they gather the votes, so the page keeps to this
year's releases, with a month of slack into the new year (`recentYear`); the
new arrivals of the recommendations keep to the same.

The catalogue holds the movies with at least `imdb.sync.minimumVoteCount`
votes (1000, or `IMDB_MINIMUM_VOTE_COUNT`). A movie stays once it is in, even
when its votes drop, because watchlist entries refer to it; lowering the
threshold adds the movies that newly qualify on the next run.
`IMDB_SYNC_ENABLED=false` switches both the import and the sync off.

## What to watch next

The For You page and the home page recommend movies from the visitor's
lists, with a reason on every card, through nothing but Storm and the
catalogue. A thumbs up or down on a seen movie (`movie_seen.liked`, V5)
weighs its people and genres for or against. The engine
([`RecommendationService`](src/main/kotlin/st/orm/demo/imdb/service/RecommendationService.kt))
runs in three steps: the taste profile from the seen list and the watchlist,
two queries bringing the seeds' genres and the credits their taste is read
from, the top-billed cast and the director, weighed per genre, per person
(a director above an actor) and per decade; the candidates from a few
bounded queries by index, the filmographies of the favourite people, the
best-rated movies of the favourite genres and of the catalogue, and the
most viewed of the week; and the ranking in one Kotlin function
([`RecommendationRanker`](src/main/kotlin/st/orm/demo/imdb/service/RecommendationRanker.kt))
that adds up genre, person and decade affinity, a vote-weighted rating,
recency and trending, scales the sum by how the movie's rating fits the
range the visitor usually watches in (the mean and spread of their seen
movies' ratings, once five are seen: a movie rated far outside it keeps a
share of its score), caps the picks per person, and names the reasons and
the seed of each pick.

The picks are stored per visitor (`recommendation`, with the reasons as a
`@Json` column) and replaced in one transaction by a background worker that
the list toggles and sign-ins queue, so the pages read a table and the slow
database does the work once per change. A visitor whose lists are empty
gets the catalogue's picks, the same ranking with an empty profile, shared
for an hour. What comes of a pick is recorded (`recommendation_event`:
opened, saved, seen, dismissed) and counted on the statistics page.

Three things keep the picks from going stale. What happened lately counts
most: a seed's weight halves every ninety days since it was seen, saved or
dismissed, down to a quarter, so a taste that moved on is followed. Each
pick counts how often the home page or the first For You window showed it
(`shown_count`, one batched update per page, V6), and the count decays its
score at the next computation, so picks passed over make way; a pick the
visitor opened starts counting afresh. And "Not interested" on a card
(`recommendation_dismissal`, keyed like the lists) keeps the movie out for
good and weighs a little against its people, genres and decade.

The view log behind the trending term grows by every visit, a few hundred
thousand rows a week once crawlers come by. A nightly job
([`ViewLogService`](src/main/kotlin/st/orm/demo/imdb/service/ViewLogService.kt))
folds the views older than a month (`imdb.viewLog.keep`) into one row per
movie and day (`movie_view_day`, V7) and removes them, in one transaction:
a template SELECT counts them per movie and date, a batched upsert writes
the days, a predicate delete clears the log. The week's counts themselves
are made once an hour and shared across computations.

New arrivals get their turn too. A movie that joined the catalogue in the
last thirty days gets a push that fades over the month, so it enters the
list when it fits, with "New in the catalogue" on its card. The For You
page opens with the visitor's picks among what joined since their previous
visit, never less than a week back and never more than a quarter, and
leaves that strip out when nothing new fits.

## Importing from IMDb or TMDB

IMDb has no API and no sign-in for other sites, but it exports a user's
ratings and lists as CSV, and so does TMDB. The Seen page takes either file
and tells them apart by the header: every movie in it is marked as seen on
the day the export has for it, the day rated or else added to the list,
which also replaces the day of a movie already marked; a rating of 8 or more
becomes a thumbs up and 5 or less a thumbs down, unless the visitor gave one
here. The columns are found by name
([`ImdbExport`](src/main/kotlin/st/orm/demo/imdb/service/ImdbExport.kt),
[`TmdbExport`](src/main/kotlin/st/orm/demo/imdb/service/TmdbExport.kt)), the
movies read by id in one query, the seen rows written in one batched upsert
keyed by the pair of user and movie, and the watchlist cleared in one delete
(`MovieSeenService.importHistory`).

## Movie sources

IMDb and TMDB have the same place in the data model: one table, `movie_source`
(V16), holds where each movie is known, a row per source, told apart by its
`kind`. It is a sealed Single-Table entity,
[`MovieSource`](src/main/kotlin/st/orm/demo/imdb/model/MovieSource.kt): every
source names the movie's id there (`externalId`), and what a source gives
besides is a field of its own subtype, like TMDB's trailer. Each kind has its
own repository, whose queries see only its rows. Adding a source is adding a
subtype, and the compiler points at each `when` over the sources that has to
handle it. The
catalogue is synced from IMDb's datasets, so a movie's own key is its IMDb id,
and the sync records the IMDb source of every new movie.

An export names its movies by a source's ids
([`ExportRow`](src/main/kotlin/st/orm/demo/imdb/service/ImdbExport.kt)), and
the import finds them through that source's rows
([`MovieSourceService`](src/main/kotlin/st/orm/demo/imdb/service/MovieSourceService.kt)):
an IMDb id among the IMDb sources, a TMDB id among the TMDB ones, and a TMDB
id not stored yet from TMDB's API, a few calls at a time, stored for the next
import ([`TmdbService.movieIds`](src/main/kotlin/st/orm/demo/imdb/service/TmdbService.kt)).

## Trailers

The movie page and the featured movie on the home page get a Trailer button
when TMDB has a trailer for the movie, which plays from YouTube without its
cookies in a dialog over the page. The trailer also plays behind the hero,
muted and blurred, in place of the backdrop image: a small player, so YouTube
sends a small stream, scaled up to cover, phones included; it is left out
for visitors who ask for less motion or less data. The page asks for it after it loads
(`/api/trailer/{id}`); the first ask looks the movie up on TMDB by its IMDb
id and stores the TMDB id and the trailer, none included, as the movie's TMDB
source. A stored answer is kept six months, the most TMDB's terms allow: until
then pages read it from the database, without a call to TMDB. Both need a TMDB API key (`TMDB_API_KEY`, a v3 key or a
v4 read access token); without one there are no trailers, and a TMDB export
brings only the movies it gives IMDb ids for.

## Check-ins and history

A seen movie is a check-in or history (`movie_seen.historic`, V12). Marking a
movie seen on its page asks when: today is a check-in, which shows its day on
the tags and to friends; earlier is history, which never shows a day. The
catch-up page (`/seen/catch-up`) is for history alone: the most voted movies
first, of every genre or one (`RatingRepository.scrollMostVoted`, the genre
join composed in only when a genre is chosen), each marked or unmarked with a
tap, a search to find one, and a switch to hide the ones marked. An IMDb
import makes check-ins of the titles it has days for. The recommendations
weigh history as old taste, and the friends feed shows check-ins only.

## Friends and clubs

Friends and clubs share the Community pages. A signed-in visitor invites another with a link, a personal one accepted once
within two weeks, or a public one to post anywhere, accepted by anyone until
replaced; accepting needs signing in too. A
friendship is two rows, one each way (`friendship`), so a visitor's friends are
a lookup of their own rows. Friends see each other's latest check-ins, on the
Friends page and the home page, as far as each shares them: three switches on
the account (`share_seen`, `share_name`, `share_dates`), all on by default,
decide whether friends see the movies at all, the name with them (else "a
friend"), and the days. There is no user directory: a link, or a club both
are in, is the only way in.

A club is a film club of coworkers or friends (`club`, `club_member`, V14).
Whoever starts one is its first admin; only admins invite, rename the club,
and make others admins or remove them, and a club always keeps one admin:
those changes lock the club row first (`ClubRepository.lock`, `SELECT ... FOR
UPDATE`), so two admins stepping down at once cannot both leave. Members need
not be friends. What a member shares is their own choice: club defaults on the
account (`club_share_*`, the days off by default) or settings for one club,
nullable columns on the membership where `NULL` follows the defaults. A club's
page shows the members' latest check-ins and what most of them have seen, a
grouped count on the seen list with `HAVING`
([`MovieSeenRepository.countSeenBy`](src/main/kotlin/st/orm/demo/imdb/repository/MovieSeenRepository.kt)).
Movies seen by a friend get a yellow tag on every list, those seen only by
club members a purple one.

Club members become friends in two taps: each name in the member list has an
Add friend button, and the request
([`FriendRequest`](src/main/kotlin/st/orm/demo/imdb/model/FriendRequest.kt),
V15) waits on the other's Friends page, with a count on the Community tab,
until they accept. Asking someone who asked you makes you friends at once.
"Not now" is a flag on the request: it leaves the recipient's list, and the
requester still sees it as sent, so nobody learns they were turned down. A
requester withdraws a request, and either friend ends a friendship, both
silently. A request is keyed by the pair, one each way at most, names the
club it came from, and goes when either of the two leaves that club.

Invites of both kinds live in one table, as a sealed Single-Table entity:
[`Invite`](src/main/kotlin/st/orm/demo/imdb/model/Invite.kt) is a
`FriendInvite` or a `ClubInvite`, told apart by the `kind` column, and the one
invite page (`/invite/{token}`) reads either by its token and handles both in
an exhaustive `when`
([`InviteService`](src/main/kotlin/st/orm/demo/imdb/service/InviteService.kt)).
This needs foreign keys and subtype queries in single-table hierarchies,
which Storm adds in 1.16.0
([storm-framework#754](https://github.com/storm-orm/storm-framework/issues/754)).

## Insights

The Insights page (`/insights`) tells visitors what their seen list says
about them: their favourite actors and directors, their genres and decades,
the movies that stand out, and the IMDB ratings they watch as a histogram
with the bell curve the recommendations use laid over it. The counts are
aggregate queries joined through the seen list on the movie, with `GROUP BY`
on an entity and `HAVING` for the favourites, so a person on one of the
visitor's movies is no favourite yet
([`PrincipalRepository.countSeenPeople`](src/main/kotlin/st/orm/demo/imdb/repository/PrincipalRepository.kt)).
The rest is read from the seen movies and their ratings in
[`InsightsService`](src/main/kotlin/st/orm/demo/imdb/service/InsightsService.kt).

## Personal lists without a sign-up

The watchlist and the seen list belong to the visitor. Every visitor has an
account from the first page: a random id in a cookie
([`web/Accounts.kt`](src/main/kotlin/st/orm/demo/imdb/web/Accounts.kt)), whose
row (`app_user`, V4) is written on the first save. Both lists are keyed by
the pair of user and movie (`Watchlist`, `MovieSeen`), so a toggle is a
lookup of the pair key, and marking a movie seen takes it off the watchlist
in the same route transaction.

Deployed on Freepod, visitors can also sign in with Freepod: the platform
signs them in and hands the application the account in request headers
([`FreepodSignIn`](src/main/kotlin/st/orm/demo/imdb/service/FreepodSignIn.kt)).
Anywhere else a client could set those headers, so the application reads
them, and offers the button, only where `accounts.freepod.enabled` says it
runs on Freepod with its sign-in on: `auth` enabled in `.freepod.json`, every
path public, and `FREEPOD_AUTH=true`, the variable meant to come from the
platform wherever auth is on; until it does, `freepod var set FREEPOD_AUTH=true`. Signed in with one provider, the
account page links another: a login that is new is added to the account, and
one that leads to an account of its own joins the two, lists and logins.

Every list of movies tags the ones the visitor has seen, with the day they
marked it: one query reads the seen list as movie ids and days
(`MovieSeenRepository.findSeenDates`, the movie selected as its foreign key,
so no movie is loaded), the page fetches it once, and a small script tags the
cards and rows, including those the infinite scroll adds.

The featured movie at the top of the home page is one of the visitor's For
You picks, drawn at random from the first window each time the page loads,
and the "What to watch next" row under it leaves it out; a new visitor gets
one of the catalogue's picks the same way.

Recently viewed on the home page is the visitor's own too. A view records the
account (`movie_view.user_id`, V9) when the browser came back with its
cookie: a first page and a crawler, which keeps no cookies, record none, so
they write no account rows. A partial index holds just the views with an
account, and a sign-in that merges a browser's account moves its views along.

A movie page has a share button: the device's share sheet where there is
one, else a menu that copies the link, posts on X or emails it. The page carries Open Graph tags
(`ShareCard` in
[`web/MovieController.kt`](src/main/kotlin/st/orm/demo/imdb/web/MovieController.kt)),
so a shared link unfurls into a card with the poster and the movie's line
(`MovieDetail.caption`).

Signing in with a git provider (GitHub, GitLab, Bitbucket or Codeberg) links
the provider account to the cookie account (`user_login`), which is what
carries the lists to another browser: a sign-in that finds the provider
account linked already switches to that account and moves over what the new
browser saved meanwhile
([`AccountService`](src/main/kotlin/st/orm/demo/imdb/service/AccountService.kt)).
The OAuth 2 exchange is a few lines over the JDK HTTP client
([`GitProviders.kt`](src/main/kotlin/st/orm/demo/imdb/service/GitProviders.kt)).
A provider is offered when its client id and secret are in the environment
(`GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET`, and likewise `GITLAB_`,
`BITBUCKET_`, `CODEBERG_`), with `<origin>/signin/<provider>/callback` as the
registered callback URL.

## Parallel query on a small database

PostgreSQL runs a large scan with parallel worker processes. On a database
with a small CPU allowance, such as the one the
[live demo](https://movies.orm.st) runs on, the workers compete for the same
CPU and each one loads `unaccent`'s rules again before its first row, so the
search suggestions took about twice as long with them as without. The setting
belongs to the host rather than the schema, so it is applied once to that
database instead of in a migration:

```sql
DO $$ BEGIN
    EXECUTE format('ALTER DATABASE %I SET max_parallel_workers_per_gather = 0', current_database());
END $$;
```

## Testing

```bash
./gradlew test
```

Repository tests run on an in-memory H2 database via `@StormTest`.
`EntitySchemaValidationTest` runs on PostgreSQL instead, through
`@StormTest(database = POSTGRESQL)`: Storm starts a Testcontainers-managed
PostgreSQL once per test run and applies the Flyway migration to it, so the
entities are validated against the schema and dialect the application deploys
with. That one test needs Docker, like running the application does.

The Playwright interface tests run against a live application, which can be
the native binary:

```bash
./gradlew installPlaywrightBrowsers                    # once
./build/native/nativeCompile/storm-imdb-ktor-graalvm    # in one terminal
./gradlew e2eTest                                      # in another
```

## Project layout

```
src/main/kotlin/st/orm/demo/imdb/
├── Application.kt      Ktor module: Storm plugin, Thymeleaf, DI, routing
├── FlywayResources.kt  Explicit Flyway resources (native images cannot scan)
├── Dependencies.kt     Service wiring via ktor-server-di
├── model/              Storm entities (@PK, @FK)
├── repository/         EntityRepository interfaces with QueryBuilder queries
├── service/            Business logic in suspend `transaction { }` blocks,
│                       plus the daily IMDB sync
├── web/                Page routes and REST routes (/api/**)
└── serialization/      kotlinx.serialization support
src/main/resources/
├── META-INF/native-image/…/   Application-level reachability metadata
├── db/migration/               Flyway schema (V1__create_schema.sql)
├── templates/                  Thymeleaf views
└── static/                     CSS, JS, images
```
