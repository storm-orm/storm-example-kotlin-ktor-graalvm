// Client-side behavior for the Storm IMDB demo: search auto-complete with
// debounce + abort, keyset-cursor infinite scrolling, and the watchlist and
// seen toggles. No frameworks — the interesting part of this demo is the backend.

document.addEventListener('DOMContentLoaded', () => {
    setupSearchBoxes();
    fitPlaceholders();
    setupSearchShortcut();
    setupInfiniteScroll();
    setupListToggles();
    setupShare();
    setupSeenTags();
    setupImportDrop();
    setupDismissals();
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

// Long enough to skip the keystrokes of a word typed in one go, short enough that the
// dropdown follows the typing; a request still in flight is aborted by the next one.
const SUGGESTION_DEBOUNCE_MS = 150;
// Suggestions already fetched in this page, by query, so backspacing or retyping a
// query shows its suggestions at once; the oldest are dropped past the limit.
const SUGGESTION_CACHE_LIMIT = 50;

function setupSearchBoxes() {
    document.querySelectorAll('[data-search-box]').forEach((searchBox, boxIndex) => {
        const input = searchBox.querySelector('[data-search-input]');
        const panel = searchBox.querySelector('[data-search-suggestions]');
        let debounceTimer = null;
        let abortController = null;
        const cache = new Map();
        // The highlighted suggestion, moved with the arrow keys or the mouse; -1 is none.
        let activeIndex = -1;

        // A combobox owning a listbox: screen readers announce the highlighted
        // option while focus stays in the input.
        panel.id = 'search-suggestions-' + boxIndex;
        panel.setAttribute('role', 'listbox');
        input.setAttribute('role', 'combobox');
        input.setAttribute('aria-autocomplete', 'list');
        input.setAttribute('aria-controls', panel.id);
        input.setAttribute('aria-expanded', 'false');

        input.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            const query = input.value.trim();
            if (query.length < 2) {
                // Cancel a request in flight too, or its late response reopens the panel.
                abortPending();
                hideSuggestions();
                return;
            }
            const cached = cache.get(query);
            if (cached) {
                abortPending();
                renderSuggestions(cached);
                return;
            }
            debounceTimer = setTimeout(() => fetchSuggestions(query), SUGGESTION_DEBOUNCE_MS);
        });

        input.addEventListener('keydown', event => {
            const options = suggestionOptions();
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                if (options.length === 0) return;
                event.preventDefault();
                const step = event.key === 'ArrowDown' ? 1 : -1;
                // Wraps around; from no highlight, down starts at the top and up at the bottom.
                const start = activeIndex === -1 ? (step === 1 ? -1 : options.length) : activeIndex;
                setActive((start + step + options.length) % options.length);
            } else if (event.key === 'Enter') {
                event.preventDefault();
                if (activeIndex >= 0 && options[activeIndex]) {
                    window.location.href = options[activeIndex].href;
                    return;
                }
                const query = input.value.trim();
                if (query) {
                    window.location.href = '/search?query=' + encodeURIComponent(query);
                }
            } else if (event.key === 'Escape') {
                hideSuggestions();
            }
        });

        // The mouse moves the same highlight, so keyboard and pointer never show two.
        panel.addEventListener('mousemove', event => {
            const option = event.target.closest('.suggestion');
            if (option) setActive(suggestionOptions().indexOf(option));
        });

        document.addEventListener('click', event => {
            if (!searchBox.contains(event.target)) {
                hideSuggestions();
            }
        });

        function abortPending() {
            if (abortController) {
                abortController.abort();
                abortController = null;
            }
        }

        async function fetchSuggestions(query) {
            // Abort the in-flight request before sending the next one.
            abortPending();
            abortController = new AbortController();
            try {
                const response = await fetch(
                    '/api/search/suggestions?query=' + encodeURIComponent(query),
                    { signal: abortController.signal }
                );
                if (!response.ok) return;
                const suggestions = await response.json();
                cache.set(query, suggestions);
                if (cache.size > SUGGESTION_CACHE_LIMIT) {
                    cache.delete(cache.keys().next().value);
                }
                renderSuggestions(suggestions);
            } catch (error) {
                if (error.name !== 'AbortError') {
                    console.error('Suggestion request failed', error);
                }
            }
        }

        function suggestionOptions() {
            return Array.from(panel.querySelectorAll('.suggestion'));
        }

        function setActive(index) {
            const options = suggestionOptions();
            options.forEach((option, i) => {
                const active = i === index;
                option.classList.toggle('active', active);
                option.setAttribute('aria-selected', String(active));
            });
            activeIndex = index;
            if (index >= 0 && options[index]) {
                input.setAttribute('aria-activedescendant', options[index].id);
                options[index].scrollIntoView({ block: 'nearest' });
            } else {
                input.removeAttribute('aria-activedescendant');
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
            suggestionOptions().forEach((option, i) => {
                option.id = panel.id + '-option-' + i;
                option.setAttribute('role', 'option');
            });
            // New results start without a highlight: Enter searches until an arrow picks one.
            setActive(-1);
            panel.hidden = false;
            input.setAttribute('aria-expanded', 'true');
        }

        function hideSuggestions() {
            panel.hidden = true;
            panel.replaceChildren();
            activeIndex = -1;
            input.removeAttribute('aria-activedescendant');
            input.setAttribute('aria-expanded', 'false');
        }
    });
}

/**
 * Cmd+F on macOS and Ctrl+F elsewhere focus the search box: the hero's on the
 * home page, the navbar's on the others. Pressed again from inside the box it
 * falls through to the browser's own find, so searching the page stays one
 * keystroke away.
 */
function setupSearchShortcut() {
    document.addEventListener('keydown', event => {
        const modifier = navigator.platform.toUpperCase().includes('MAC') ? event.metaKey : event.ctrlKey;
        if (!modifier || event.altKey || event.shiftKey || event.key.toLowerCase() !== 'f') return;
        const inputs = Array.from(document.querySelectorAll('[data-search-input]'));
        if (inputs.includes(document.activeElement)) return;
        // The hero comes after the navbar in the page, so the last visible box is the prominent one.
        const input = inputs.filter(it => it.offsetParent !== null).pop();
        if (!input) return;
        event.preventDefault();
        input.focus();
        input.select();
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
    // The dropdown is on screen the moment it renders, so its images load at once.
    media.loading = 'eager';
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
                window_.items.forEach(item => appendItem(sentinel.dataset.kind, target, item));
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
    if (kind === 'person') return personCard(item);
    if (kind === 'recommended') return recommendedCard(item);
    return movieCard(item);
}

/**
 * Appends an item of a window to the target. A movie of the recently added
 * page goes under the day it joined the catalogue: the last day on the page
 * when it is that day, a new day otherwise, so a day that continues in the
 * next window continues on the page. Anything else is a card in a grid.
 */
function appendItem(kind, target, item) {
    if (kind !== 'addition') {
        target.appendChild(renderCard(kind, item));
        return;
    }
    let day = target.lastElementChild;
    if (!day || day.dataset.day !== item.day) {
        day = document.createElement('section');
        day.className = 'day-group';
        day.dataset.day = item.day;
        const header = document.createElement('div');
        header.className = 'section-header';
        const title = document.createElement('h2');
        title.className = 'section-title';
        title.textContent = item.day;
        header.appendChild(title);
        day.appendChild(header);
        const grid = document.createElement('div');
        grid.className = 'movie-grid';
        day.appendChild(grid);
        target.appendChild(day);
    }
    day.querySelector('.movie-grid').appendChild(movieCard(item));
}

// A recommended movie: the movie card, linked as coming from a recommendation,
// with its reasons underneath and a "Not interested" control beside it, as the
// server renders it (fragments/cards :: recommended).
function recommendedCard(item) {
    const card = movieCard(item);
    card.href = '/movie/' + item.id + '?via=for-you';
    const reasons = document.createElement('div');
    reasons.className = 'movie-card-reasons';
    item.reasons.forEach(text => {
        const reason = document.createElement('span');
        reason.textContent = text;
        reasons.appendChild(reason);
    });
    card.querySelector('.movie-card-info').appendChild(reasons);

    const wrapper = document.createElement('div');
    wrapper.className = 'recommended';
    wrapper.dataset.movieId = item.id;
    const dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.className = 'dismiss-button';
    dismiss.dataset.dismiss = '';
    dismiss.dataset.movieId = item.id;
    dismiss.title = 'Not interested';
    dismiss.setAttribute('aria-label', 'Not interested');
    dismiss.textContent = '×';
    wrapper.appendChild(card);
    wrapper.appendChild(dismiss);
    return wrapper;
}

/* ── Not interested ───────────────────────────────────────────────── */

// One listener on the document, since the scroll appends cards after load.
// The card goes at once; the server keeps the movie out of the picks for good.
function setupDismissals() {
    document.addEventListener('click', async event => {
        const button = event.target.closest('[data-dismiss]');
        if (!button) return;
        event.preventDefault();
        button.disabled = true;
        try {
            const response = await fetch('/api/for-you/' + button.dataset.movieId + '/dismiss', { method: 'POST' });
            if (!response.ok) return;
            document.querySelectorAll('.recommended[data-movie-id="' + button.dataset.movieId + '"]').forEach(card => card.remove());
        } finally {
            button.disabled = false;
        }
    });
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

/* ── Watchlist and seen toggles ───────────────────────────────────── */

// Every toggle answers with the state of both lists and the vote: marking a
// movie seen takes it off the watchlist, so the other button follows, and
// the thumbs appear once the movie is seen.
function setupListToggles() {
    const buttons = document.querySelectorAll('[data-list-toggle]');
    const voteButtons = document.querySelectorAll('[data-vote]');
    const voteGroup = document.querySelector('[data-vote-buttons]');
    const render = state => {
        buttons.forEach(button => {
            const onList = button.dataset.listToggle === 'watchlist' ? state.onWatchlist : state.seen;
            button.classList.toggle('active', onList);
            const label = button.querySelector('span');
            label.textContent = button.dataset.listToggle === 'watchlist'
                ? (onList ? 'On watchlist' : 'Add to watchlist')
                : (onList ? seenLabel('Seen', state.seenAt) : 'Mark as seen');
        });
        if (voteGroup) voteGroup.hidden = !state.seen;
        voteButtons.forEach(button => button.classList.toggle('active', String(state.liked) === button.dataset.vote));
    };
    // The page renders the seen button's plain label; the day it was marked joins it here, as on the tags.
    buttons.forEach(button => {
        if (button.dataset.seenAt) button.querySelector('span').textContent = seenLabel('Seen', button.dataset.seenAt);
    });
    const all = [...buttons, ...voteButtons];
    const submit = async (url, body) => {
        all.forEach(button => button.disabled = true);
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: body ? { 'Content-Type': 'application/json' } : {},
                body: body ? JSON.stringify(body) : undefined,
            });
            if (!response.ok) return;
            render(await response.json());
        } finally {
            all.forEach(button => button.disabled = false);
        }
    };
    buttons.forEach(button => {
        button.addEventListener('click', () =>
            submit('/api/' + button.dataset.listToggle + '/' + button.dataset.movieId));
    });
    // A thumb pressed again takes the vote back.
    voteButtons.forEach(button => {
        button.addEventListener('click', () => {
            const liked = button.classList.contains('active') ? null : button.dataset.vote === 'true';
            submit('/api/seen/' + button.dataset.movieId + '/vote', { liked });
        });
    });
}

// The share button on a movie page. Where the device has a share sheet (phones:
// messages, WhatsApp, X, ...) it opens that; elsewhere a small menu copies the
// link, posts on X or emails it. The message is visual either way: the link
// unfurls into a card with the poster, from the Open Graph tags in the head,
// so the poster is not attached as well, which would show it twice.
function setupShare() {
    const button = document.querySelector('[data-share]');
    if (!button) return;
    const menu = document.querySelector('[data-share-menu]');
    const { shareTitle: title, shareText: text, shareUrl: url } = button.dataset;
    const message = title + ' · ' + text;
    menu.querySelector('[data-share-x]').href =
        'https://x.com/intent/post?text=' + encodeURIComponent(message) + '&url=' + encodeURIComponent(url);
    menu.querySelector('[data-share-mail]').href =
        'mailto:?subject=' + encodeURIComponent(title) + '&body=' + encodeURIComponent(message + '\n' + url);
    const copy = menu.querySelector('[data-share-copy]');
    copy.addEventListener('click', async () => {
        await navigator.clipboard.writeText(url);
        copy.textContent = 'Link copied';
        setTimeout(() => { copy.textContent = 'Copy link'; menu.hidden = true; }, 1200);
    });
    button.addEventListener('click', async () => {
        if (!navigator.share) {
            menu.hidden = !menu.hidden;
            return;
        }
        try {
            await navigator.share({ title, text: message, url });
        } catch (e) {
            // The sheet was dismissed.
        }
    });
    document.addEventListener('click', event => {
        if (!event.target.closest('.share')) menu.hidden = true;
    });
}

// The IMDb import on the seen page: a file dropped on the zone, or chosen with
// its +, is sent at once. The form stays an ordinary upload, so the page works
// without this script, with its Import button.
function setupImportDrop() {
    const form = document.querySelector('[data-import-form]');
    if (!form) return;
    const zone = form.querySelector('[data-drop-zone]');
    const input = form.querySelector('[data-import-file]');
    const text = form.querySelector('[data-drop-text]');
    form.querySelector('[data-import-submit]').hidden = true;
    const send = () => {
        if (!input.files.length) return;
        zone.classList.add('importing');
        text.textContent = 'Importing ' + input.files[0].name + '…';
        form.submit();
    };
    input.addEventListener('change', send);
    ['dragenter', 'dragover'].forEach(type => zone.addEventListener(type, event => {
        event.preventDefault();
        zone.classList.add('dragging');
    }));
    ['dragleave', 'drop'].forEach(type => zone.addEventListener(type, () => zone.classList.remove('dragging')));
    zone.addEventListener('drop', event => {
        event.preventDefault();
        if (!event.dataTransfer.files.length) return;
        input.files = event.dataTransfer.files;
        send();
    });
}

// A search box says what it searches where that fits, and just "Search" where
// it does not: the full placeholder is measured in the box's own font against
// the room inside it, again when the window resizes and once the web font is in.
function fitPlaceholders() {
    const inputs = [...document.querySelectorAll('[data-placeholder-full]')];
    if (!inputs.length) return;
    const canvas = document.createElement('canvas').getContext('2d');
    inputs.forEach(input => input.dataset.placeholderShort = input.placeholder);
    const fit = () => inputs.forEach(input => {
        const style = getComputedStyle(input);
        canvas.font = style.font;
        const room = input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
        const full = input.dataset.placeholderFull;
        input.placeholder = canvas.measureText(full).width <= room ? full : input.dataset.placeholderShort;
    });
    fit();
    window.addEventListener('resize', fit);
    if (document.fonts) document.fonts.ready.then(fit);
}

// The day a movie was marked seen, as the tags and the movie page show it.
const SEEN_DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

// A seen label with its day, "✓ 14 Mar 2021", or the label alone when the movie
// was marked within the last month: a recent one needs no date.
function seenLabel(label, seenAt) {
    if (!seenAt) return label;
    const date = new Date(seenAt);
    const monthAgo = new Date();
    monthAgo.setMonth(monthAgo.getMonth() - 1);
    return date > monthAgo ? label : label + ' ' + SEEN_DAY.format(date);
}

// Every list of movies tags the ones the visitor has seen, with the day they
// marked it, or the checkmark alone when that was within the last month: the
// seen dates come in one request, by movie id, and the tags go on the cards
// and rows on the page, and on those the scroll adds later.
async function setupSeenTags() {
    let dates;
    try {
        const response = await fetch('/api/seen');
        if (!response.ok) return;
        dates = await response.json();
    } catch (e) {
        return;
    }
    if (!Object.keys(dates).length) return;
    const tagAll = () => document.querySelectorAll('a.movie-card, a.ranked-row').forEach(link => {
        const id = (link.getAttribute('href').match(/^\/movie\/(tt\d+)/) || [])[1];
        if (!id || !dates[id] || link.querySelector('.seen-tag')) return;
        const tag = document.createElement('span');
        tag.className = 'seen-tag';
        tag.textContent = seenLabel('✓', dates[id]);
        tag.title = 'Seen ' + SEEN_DAY.format(new Date(dates[id]));
        (link.querySelector('.ranked-sub') || link.querySelector('.poster')).appendChild(tag);
    });
    tagAll();
    new MutationObserver(tagAll).observe(document.body, { childList: true, subtree: true });
}
