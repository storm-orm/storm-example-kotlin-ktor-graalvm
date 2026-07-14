// Client-side behavior for the Storm IMDB demo: search auto-complete with
// debounce + abort, keyset-cursor infinite scrolling, and the watchlist
// toggle. No frameworks — the interesting part of this demo is the backend.

document.addEventListener('DOMContentLoaded', () => {
    setupSearchBoxes();
    setupInfiniteScroll();
    setupWatchlistToggle();
    setupSummaries();
    setupBackdrops();
    setupGalleries();
});

/* ── Photo galleries (fetched once, then served from the database) ── */

function setupGalleries() {
    document.querySelectorAll('[data-gallery-endpoint]').forEach(async grid => {
        try {
            const response = await fetch(grid.dataset.galleryEndpoint);
            if (!response.ok) return;
            const photos = await response.json();
            if (photos.length === 0) return;
            photos.forEach(photo => {
                const figure = document.createElement('figure');
                figure.className = 'gallery-photo';
                const image = document.createElement('img');
                image.src = photo.url;
                image.alt = photo.caption ?? '';
                image.title = photo.caption ?? '';
                image.loading = 'lazy';
                image.addEventListener('error', () => figure.remove());
                figure.appendChild(image);
                grid.appendChild(figure);
            });
            grid.closest('section').hidden = false;
        } catch (error) {
            // Galleries are optional enrichment — the page is fine without them.
        }
    });
}

/* ── Netflix-style backdrops (optional enrichment) ────────────────── */

function setupBackdrops() {
    document.querySelectorAll('[data-backdrop-source]').forEach(element => {
        const backdrop = new Image();
        backdrop.onload = () => {
            element.style.backgroundImage = 'url("' + element.dataset.backdropSource + '")';
            element.classList.add('backdrop-image');
        };
        // On error the blurred poster fallback simply stays in place.
        backdrop.src = element.dataset.backdropSource;
    });
}

/* ── Wikipedia summaries (optional enrichment) ────────────────────── */

function setupSummaries() {
    document.querySelectorAll('[data-summary-endpoint]').forEach(async element => {
        try {
            const response = await fetch(element.dataset.summaryEndpoint);
            if (!response.ok) return;
            const summary = await response.json();
            if (!summary.extract) return;
            element.textContent = summary.extract;
            element.hidden = false;
        } catch (error) {
            // Summaries are optional enrichment — the page is fine without them.
        }
    });
}

/* ── Search auto-complete ─────────────────────────────────────────── */

const SUGGESTION_DEBOUNCE_MS = 300;

function setupSearchBoxes() {
    document.querySelectorAll('[data-search-box]').forEach(searchBox => {
        const input = searchBox.querySelector('[data-search-input]');
        const panel = searchBox.querySelector('[data-search-suggestions]');
        let debounceTimer = null;
        let abortController = null;

        input.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            const query = input.value.trim();
            if (query.length < 2) {
                hideSuggestions();
                return;
            }
            debounceTimer = setTimeout(() => fetchSuggestions(query), SUGGESTION_DEBOUNCE_MS);
        });

        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                const query = input.value.trim();
                if (query) {
                    window.location.href = '/search?query=' + encodeURIComponent(query);
                }
            } else if (event.key === 'Escape') {
                hideSuggestions();
            }
        });

        document.addEventListener('click', event => {
            if (!searchBox.contains(event.target)) {
                hideSuggestions();
            }
        });

        async function fetchSuggestions(query) {
            // Abort the in-flight request before sending the next one.
            if (abortController) {
                abortController.abort();
            }
            abortController = new AbortController();
            try {
                const response = await fetch(
                    '/api/search/suggestions?query=' + encodeURIComponent(query),
                    { signal: abortController.signal }
                );
                if (!response.ok) return;
                renderSuggestions(await response.json());
            } catch (error) {
                if (error.name !== 'AbortError') {
                    console.error('Suggestion request failed', error);
                }
            }
        }

        function renderSuggestions({ movies, persons }) {
            if (movies.length === 0 && persons.length === 0) {
                hideSuggestions();
                return;
            }
            panel.replaceChildren(
                ...movies.map(movie => suggestionLink(
                    '/movie/' + movie.id, 'movie', 'Movie', movie.title, movie.year, '/api/poster/' + movie.id)),
                ...persons.map(person => suggestionLink(
                    '/person/' + person.id, 'person', 'Person', person.name, null, '/api/photo/' + person.id))
            );
            panel.hidden = false;
        }

        function hideSuggestions() {
            panel.hidden = true;
            panel.replaceChildren();
        }
    });
}

function suggestionLink(href, kind, kindLabel, text, year, imageUrl) {
    const link = document.createElement('a');
    link.className = 'suggestion';
    link.href = href;

    const media = document.createElement('img');
    media.className = 'suggestion-media suggestion-media-' + kind;
    media.src = imageUrl;
    media.alt = '';
    media.loading = 'lazy';
    media.addEventListener('error', () => media.classList.add('failed'));
    link.appendChild(media);

    const type = document.createElement('span');
    type.className = 'suggestion-type suggestion-type-' + kind;
    type.textContent = kindLabel;
    link.appendChild(type);

    const label = document.createElement('span');
    label.className = 'suggestion-text';
    label.textContent = text;
    link.appendChild(label);

    if (year) {
        const yearLabel = document.createElement('span');
        yearLabel.className = 'suggestion-year';
        yearLabel.textContent = year;
        link.appendChild(yearLabel);
    }
    return link;
}

/* ── Infinite scroll (keyset cursors) ─────────────────────────────────
   Each response carries an opaque `nextCursor`. The client's only job is
   to echo it back verbatim on the next request — it is never inspected or
   constructed here. When no cursor remains, the sentinel is removed and
   scrolling stops. ─────────────────────────────────────────────────── */

function setupInfiniteScroll() {
    document.querySelectorAll('[data-infinite-scroll]').forEach(sentinel => {
        const target = document.getElementById(sentinel.dataset.target);
        const spinner = sentinel.querySelector('.spinner');
        let loading = false;

        const observer = new IntersectionObserver(async entries => {
            if (!entries.some(entry => entry.isIntersecting) || loading) return;
            const cursor = sentinel.dataset.cursor;
            if (!cursor) {
                finish();
                return;
            }
            loading = true;
            spinner.hidden = false;
            try {
                const separator = sentinel.dataset.endpoint.includes('?') ? '&' : '?';
                const response = await fetch(
                    sentinel.dataset.endpoint + separator + 'cursor=' + encodeURIComponent(cursor));
                if (!response.ok) {
                    finish();
                    return;
                }
                const window_ = await response.json();
                window_.items.forEach(item => target.appendChild(renderCard(sentinel.dataset.kind, item)));
                if (window_.nextCursor && window_.items.length > 0) {
                    sentinel.dataset.cursor = window_.nextCursor;
                    // Re-observe so a still-visible sentinel triggers the next window.
                    observer.unobserve(sentinel);
                    requestAnimationFrame(() => observer.observe(sentinel));
                } else {
                    finish();
                }
            } finally {
                loading = false;
                spinner.hidden = true;
            }
        }, { rootMargin: '400px' });

        observer.observe(sentinel);

        function finish() {
            observer.disconnect();
            sentinel.remove();
        }
    });
}

function renderCard(kind, item) {
    return kind === 'person' ? personCard(item) : movieCard(item);
}

function movieCard(movie) {
    const card = document.createElement('a');
    card.className = 'movie-card';
    card.href = '/movie/' + movie.id;

    const poster = document.createElement('div');
    poster.className = 'poster';

    const placeholder = document.createElement('div');
    placeholder.className = 'poster-placeholder';
    const placeholderTitle = document.createElement('span');
    placeholderTitle.textContent = movie.title;
    placeholder.appendChild(placeholderTitle);
    poster.appendChild(placeholder);

    const image = document.createElement('img');
    image.src = '/api/poster/' + movie.id;
    image.alt = '';
    image.loading = 'lazy';
    image.addEventListener('error', () => image.classList.add('failed'));
    poster.appendChild(image);
    card.appendChild(poster);

    const info = document.createElement('div');
    info.className = 'movie-card-info';
    const title = document.createElement('div');
    title.className = 'movie-card-title';
    title.textContent = movie.title;
    info.appendChild(title);
    const year = document.createElement('div');
    year.className = 'movie-card-year';
    year.textContent = movie.year ?? '';
    info.appendChild(year);
    card.appendChild(info);

    return card;
}

function personCard(person) {
    const card = document.createElement('a');
    card.className = 'person-card';
    card.href = '/person/' + person.id;

    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    const initial = document.createElement('span');
    initial.textContent = person.name.charAt(0).toUpperCase();
    avatar.appendChild(initial);
    const photo = document.createElement('img');
    photo.src = '/api/photo/' + person.id;
    photo.alt = '';
    photo.loading = 'lazy';
    photo.addEventListener('error', () => photo.classList.add('failed'));
    avatar.appendChild(photo);
    card.appendChild(avatar);

    const name = document.createElement('div');
    name.className = 'person-card-name';
    name.textContent = person.name;
    card.appendChild(name);

    return card;
}

/* ── Watchlist toggle ─────────────────────────────────────────────── */

function setupWatchlistToggle() {
    document.querySelectorAll('[data-watchlist-toggle]').forEach(button => {
        button.addEventListener('click', async () => {
            button.disabled = true;
            try {
                const response = await fetch('/api/watchlist/' + button.dataset.movieId, { method: 'POST' });
                if (!response.ok) return;
                const state = await response.json();
                button.classList.toggle('active', state.onWatchlist);
                button.querySelector('[data-watchlist-label]').textContent =
                    state.onWatchlist ? 'On watchlist' : 'Add to watchlist';
            } finally {
                button.disabled = false;
            }
        });
    });
}
