-- Test fixture: a handful of well-known movies with cast, genres, ratings,
-- and views. Genre and movie_view ids are identity-generated in insertion
-- order (genres: Action = 1, Drama = 2, Sci-Fi = 3 on a fresh database).

INSERT INTO genre (name) VALUES ('Action');
INSERT INTO genre (name) VALUES ('Drama');
INSERT INTO genre (name) VALUES ('Sci-Fi');

INSERT INTO movie (id, primary_title, original_title, start_year, runtime_minutes) VALUES
    ('tt0133093', 'The Matrix', 'The Matrix', 1999, 136),
    ('tt0234215', 'The Matrix Reloaded', 'The Matrix Reloaded', 2003, 138),
    ('tt0111161', 'The Shawshank Redemption', 'The Shawshank Redemption', 1994, 142),
    ('tt0110912', 'Pulp Fiction', 'Pulp Fiction', 1994, 154),
    ('tt0068646', 'The Godfather', 'The Godfather', 1972, 175);

INSERT INTO movie_genre (movie_id, genre_id) VALUES
    ('tt0133093', 1),
    ('tt0133093', 3),
    ('tt0234215', 1),
    ('tt0234215', 3),
    ('tt0111161', 2),
    ('tt0110912', 2),
    ('tt0068646', 2);

INSERT INTO person (id, primary_name, birth_year, death_year) VALUES
    ('nm0000206', 'Keanu Reeves', 1964, NULL),
    ('nm0000401', 'Laurence Fishburne', 1961, NULL),
    ('nm0000151', 'Morgan Freeman', 1937, NULL),
    ('nm0000209', 'Tim Robbins', 1958, NULL),
    ('nm0000237', 'John Travolta', 1954, NULL),
    ('nm0000199', 'Al Pacino', 1940, NULL);

INSERT INTO principal (movie_id, ordering, person_id, category, characters) VALUES
    ('tt0133093', 1, 'nm0000206', 'actor', 'Neo'),
    ('tt0133093', 2, 'nm0000401', 'actor', 'Morpheus'),
    ('tt0234215', 1, 'nm0000206', 'actor', 'Neo'),
    ('tt0234215', 2, 'nm0000401', 'actor', 'Morpheus'),
    ('tt0111161', 1, 'nm0000209', 'actor', 'Andy Dufresne'),
    ('tt0111161', 2, 'nm0000151', 'actor', 'Red'),
    ('tt0110912', 1, 'nm0000237', 'actor', 'Vincent Vega'),
    ('tt0068646', 1, 'nm0000199', 'actor', 'Michael Corleone');

INSERT INTO rating (movie_id, average_rating, vote_count) VALUES
    ('tt0133093', 8.7, 2000000),
    ('tt0234215', 7.2, 600000),
    ('tt0111161', 9.3, 2900000),
    ('tt0110912', 8.9, 2200000),
    ('tt0068646', 9.2, 2000000);

INSERT INTO movie_view (movie_id, viewed_at) VALUES
    ('tt0133093', TIMESTAMP '2026-07-01 10:00:00'),
    ('tt0111161', TIMESTAMP '2026-07-01 11:00:00'),
    ('tt0133093', TIMESTAMP '2026-07-01 12:00:00');
