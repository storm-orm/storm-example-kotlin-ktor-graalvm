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
    setupCatchUp();
    setupInvite();
    setupSaveButtons();
    setupImportDrop();
    setupPanelToggles();
    setupDismissals();
    setupSummaries();
    setupBackdrops();
    setupGalleries();
    setupNavbarHeight();
    setupHeroBands();
    setupTrailers();
    setupWatchProviders();
    setupServiceLogos();
    setupServiceShares();
    setupPosterGlows();
    setupPosterTilt();
    setupCardTitles();
    setupPickers();
    setupLists();
    setupQuiz();
    setupCinema();
});

// Under the mouse a poster gives a little: it tilts toward the cursor, a light follows the cursor
// over its surface, and its glow moves the other way, so the poster seems to lift off its light.
// The pointer's place over the poster goes to the stylesheet as --tilt-x and --tilt-y, from -1 to 1.
function setupPosterTilt() {
    if (!matchMedia('(hover: hover) and (prefers-reduced-motion: no-preference)').matches) return;
    let current = null;
    const settle = frame => ['--tilt-x', '--tilt-y', '--shine-x', '--shine-y', '--shine'].forEach(name => frame.style.removeProperty(name));
    document.addEventListener('pointermove', event => {
        const frame = event.target.closest?.('.poster-glow');
        if (current && current !== frame) settle(current);
        current = frame;
        if (!frame) return;
        const rect = (frame.querySelector('.poster') ?? frame).getBoundingClientRect();
        const x = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
        const y = Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height));
        frame.style.setProperty('--tilt-x', ((x - 0.5) * 2).toFixed(3));
        frame.style.setProperty('--tilt-y', ((y - 0.5) * 2).toFixed(3));
        frame.style.setProperty('--shine-x', `${(x * 100).toFixed(1)}%`);
        frame.style.setProperty('--shine-y', `${(y * 100).toFixed(1)}%`);
        frame.style.setProperty('--shine', '0.18');
    }, { passive: true });
    document.addEventListener('pointerleave', () => { if (current) settle(current); current = null; });
}

// The navbar's height, as --navbar-height, for the hero that reaches up under it: one row on a
// desktop, two on a phone, and whatever the window makes of it in between.
function setupNavbarHeight() {
    const navbar = document.querySelector('.navbar');
    if (!navbar) return;
    const measure = () => document.documentElement.style.setProperty('--navbar-height', `${navbar.offsetHeight}px`);
    new ResizeObserver(measure).observe(navbar);
    measure();
}

/* ── Card titles ───────────────────────────────────────────────────── */

// A title under a poster wraps rather than being cut short, and once it wraps, the year (or what
// stands in its place) follows on its last line, so a card takes two lines where it would take three.
// Measured again whenever a card changes width; the change itself waits for the next frame, so the
// card's new height does not call the observer back within its own round.
const cardTitles = new ResizeObserver(entries => entries.forEach(entry => {
    requestAnimationFrame(() => fitCardTitle(entry.target));
}));

function fitCardTitle(info) {
    const title = info.querySelector('.movie-card-title');
    info.classList.remove('title-wrapped');
    const wrapped = title.offsetHeight > parseFloat(getComputedStyle(title).lineHeight) * 1.5;
    info.classList.toggle('title-wrapped', wrapped);
}

function setupCardTitles() {
    document.querySelectorAll('.movie-card-info').forEach(info => {
        if (info.querySelector('.movie-card-title + .movie-card-year')) cardTitles.observe(info);
    });
}

/* ── Poster glows ─────────────────────────────────────────────────── */

// A poster lights what is around it in its own colors: once it loads, its card (or the hero's poster
// frame) takes the image as --poster, which the stylesheet blurs into a glow behind it. Posters load
// lazily and the infinite scroll adds more, so one listener catches every load as it happens.
function setupPosterGlows() {
    const glow = image => {
        const frame = image.closest('.movie-card, .hero-poster, .movie-hero-poster');
        if (!frame || image.classList.contains('failed')) return;
        frame.style.setProperty('--poster', `url("${image.currentSrc || image.src}")`);
        frame.classList.add('poster-glow');
    };
    // The poster's own image only, a child of the poster: not the logos For You puts on it.
    document.addEventListener('load', event => {
        if (event.target instanceof HTMLImageElement && event.target.parentElement?.classList.contains('poster')) glow(event.target);
    }, true);
    document.querySelectorAll('.poster > img').forEach(image => { if (image.complete && image.naturalWidth) glow(image); });
}

/* ── Trailers (from TMDB, played from YouTube without its cookies) ── */

// A Trailer button shows once its movie has a trailer. Where the hero plays the trailer behind it,
// muted and blurred, the button brings that same player forward, in the space the hero has: the
// page's content gives way, the blur and the dimming go, the sound comes on and YouTube's controls
// answer, and Exit trailer (or Escape) sends it back behind the content, playing on. Without the
// player behind the hero, for visitors who ask for less motion or less data, the trailer plays in a
// dialog.
// A hero has two Trailer buttons, one on the backdrop for phones and one with the other buttons; they
// share the one trailer, asked for once.
function setupTrailers() {
    document.querySelectorAll('.hero, .movie-hero').forEach(async hero => {
        const buttons = [...hero.querySelectorAll('[data-trailer-endpoint]')];
        if (buttons.length === 0) return;
        try {
            const response = await fetch(buttons[0].dataset.trailerEndpoint);
            if (!response.ok) return;
            const trailer = await response.json();
            const title = buttons[0].dataset.trailerTitle;
            const backdrop = hero.querySelector('.hero-backdrop, .movie-backdrop');
            const behind = backdrop ? playBehind(hero, backdrop, trailer.youtubeKey) : null;
            buttons.forEach(button => {
                button.hidden = false;
                button.addEventListener('click', async () => {
                    if (hero.sendTrailerBack) hero.sendTrailerBack();
                    else if (behind && await behind.ready) bringForward(hero, behind, buttons);
                    else openTrailer(trailer.youtubeKey, title);
                });
            });
        } catch (e) {
            // No trailer then: the page is complete without it.
        }
    });
}

/* ── Where a movie plays (from TMDB, with JustWatch's data) ────────── */

// The platforms a movie streams on in the visitor's country, as their logos; the name is the
// tooltip and what a screen reader says. Nowhere, or no country known, and the row stays hidden.
// A movie page has two rows, after the tags on a desktop and on a phone; they share one request.
function setupWatchProviders() {
    const requests = new Map();
    const platformsAt = endpoint => {
        if (!requests.has(endpoint)) requests.set(endpoint, fetch(endpoint).then(response => response.ok ? response.json() : []));
        return requests.get(endpoint);
    };
    document.querySelectorAll('[data-watch-providers-endpoint]').forEach(async row => {
        try {
            const platforms = await platformsAt(row.dataset.watchProvidersEndpoint);
            platforms.filter(platform => platform.logoUrl).forEach(platform => {
                const logo = document.createElement('img');
                logo.className = 'watch-provider';
                logo.src = platform.logoUrl;
                logo.alt = platform.name;
                logo.title = platform.name;
                logo.addEventListener('error', () => logo.remove());
                row.appendChild(logo);
            });
            row.hidden = row.childElementCount === 0;
        } catch (e) {
            // No platforms then: the page is complete without them.
        }
    });
}

/* ── Your services on the posters ──────────────────────────────────── */

// The logos of the visitor's streaming services on the posters of the movies they stream, in the rows
// and grids that ask for them (For You, What to watch next, the watchlist): asked for the cards in one
// request, and again for the cards a scroll adds. A movie on none of them, or a page without
// services, keeps its poster as it is.
function setupServiceLogos() {
    const movieIdOf = card => (card.getAttribute('href').match(/^\/movie\/(tt\d+)/) || [])[1];
    document.querySelectorAll('[data-service-logos]').forEach(container => {
        const show = async () => {
            const cards = [...container.querySelectorAll('a.movie-card:not([data-services-asked])')].filter(movieIdOf);
            if (cards.length === 0) return;
            cards.forEach(card => { card.dataset.servicesAsked = ''; });
            try {
                const ids = cards.map(movieIdOf).join(',');
                const response = await fetch(container.dataset.serviceLogos + '?movies=' + encodeURIComponent(ids));
                if (!response.ok) return;
                const services = await response.json();
                cards.forEach(card => {
                    const platforms = (services[movieIdOf(card)] || []).filter(platform => platform.logoUrl);
                    if (platforms.length === 0) return;
                    const strip = document.createElement('div');
                    strip.className = 'poster-services';
                    platforms.forEach(platform => {
                        const logo = document.createElement('img');
                        logo.src = platform.logoUrl;
                        logo.alt = platform.name;
                        logo.title = platform.name;
                        strip.appendChild(logo);
                    });
                    card.querySelector('.poster').appendChild(strip);
                });
            } catch (e) {
                // The posters are complete without the logos.
            }
        };
        new MutationObserver(show).observe(container, { childList: true });
        show();
    });
}

/* ── The share of your services (About You) ─────────────────────── */

// Where the movies the visitor watched stream, per service of theirs: a bar each, with the share and the
// count. The server looks up a few dozen more watched movies on TMDB per request, so the card asks again
// while some are unknown and draws the bars anew each time, until all are known or no more come in.
function setupServiceShares() {
    const card = document.querySelector('[data-service-shares]');
    if (!card) return;
    const rows = card.querySelector('[data-service-share-rows]');
    const note = card.querySelector('[data-service-share-note]');
    const draw = shares => {
        // The bars are as long as their share of the largest, like the genres beside them.
        const largest = Math.max(1, ...shares.services.map(service => service.percent));
        rows.replaceChildren(...shares.services.map(service => {
            const row = document.createElement('div');
            row.className = 'service-share-row';
            const label = document.createElement('span');
            label.className = 'service-share-name';
            if (service.logoUrl) {
                const logo = document.createElement('img');
                logo.src = service.logoUrl;
                logo.alt = '';
                logo.addEventListener('error', () => logo.remove());
                label.appendChild(logo);
            }
            label.appendChild(document.createTextNode(service.name));
            const track = document.createElement('div');
            track.className = 'bar-track';
            const fill = document.createElement('div');
            fill.className = 'bar-fill';
            fill.style.width = `${100 * service.percent / largest}%`;
            track.appendChild(fill);
            const value = document.createElement('span');
            value.className = 'bar-value';
            value.textContent = `${service.percent}% · ${service.movies}`;
            row.append(label, track, value);
            return row;
        }));
        const counted = `${shares.counted} of your ${shares.watched} watched movies stream on your services.`;
        note.textContent = shares.known < shares.watched
            ? `${counted} Looking up where the rest stream: ${shares.known} of ${shares.watched} known so far.`
            : counted;
        card.hidden = false;
    };
    let known = -1;
    const ask = async () => {
        try {
            const response = await fetch(card.dataset.serviceShares);
            if (!response.ok) return;
            const shares = await response.json();
            draw(shares);
            if (shares.known < shares.watched && shares.known > known) {
                known = shares.known;
                setTimeout(ask, 500);
            }
        } catch (e) {
            // The page is complete without the card.
        }
    };
    ask();
}

// On a phone, the hero's head (poster, title, genres) lies on the backdrop, or the trailer behind it,
// and its body (description, buttons) follows below: the backdrop band ends where the body begins.
function setupHeroBands() {
    document.querySelectorAll('.hero, .movie-hero').forEach(hero => {
        const body = hero.querySelector('.hero-body');
        if (!body) return;
        const measure = () => {
            const band = body.getBoundingClientRect().top - hero.getBoundingClientRect().top - 14;
            hero.style.setProperty('--band', `${Math.round(band)}px`);
        };
        new ResizeObserver(measure).observe(hero);
        measure();
    });
}

function openTrailer(youtubeKey, title) {
    const dialog = document.createElement('dialog');
    dialog.className = 'trailer-dialog';
    const frame = document.createElement('iframe');
    frame.src = `https://www.youtube-nocookie.com/embed/${encodeURIComponent(youtubeKey)}?autoplay=1&rel=0`;
    frame.title = `${title} trailer`;
    frame.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
    frame.allowFullscreen = true;
    const close = document.createElement('button');
    close.className = 'trailer-close';
    close.type = 'button';
    close.setAttribute('aria-label', 'Close the trailer');
    close.textContent = '×';
    close.addEventListener('click', () => dialog.close());
    dialog.append(close, frame);
    // A click on the backdrop, outside the player, closes it too.
    dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
}

// YouTube's IFrame API, loaded once, the first time a hero plays a trailer: it is what turns the
// sound on and off on the player that keeps playing.
let youtubeApi = null;

function loadYoutubeApi() {
    youtubeApi ??= new Promise(resolve => {
        window.onYouTubeIframeAPIReady = () => resolve(window.YT);
        const script = document.createElement('script');
        script.src = 'https://www.youtube.com/iframe_api';
        document.head.append(script);
    });
    return youtubeApi;
}

// Behind the hero, the player is small, so YouTube sends a small stream, phones included, scaled up to
// cover the hero and blurred, so its low quality does not show. It fades in once YouTube says it plays,
// past its title overlay; where a browser holds back autoplay, the backdrop image simply stays.
const BACKDROP_VIDEO_WIDTH = 480;
const BACKDROP_VIDEO_HEIGHT = 270;
const FILL_CROP = 1.08;

// The icons of the trailer's controls: the whole frame (corners out), filling the hero again (corners in), and exit.
const ICON_FULL_FRAME = '<svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8V3h5M12 3h5v5M17 12v5h-5M8 17H3v-5"/></svg>';
const ICON_FILL = '<svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 3v5H3M12 3v5h5M17 12h-5v5M3 12h5v5"/></svg>';
const ICON_EXIT = '<svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15"/></svg>';

function playBehind(hero, backdrop, youtubeKey) {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return null;
    if (navigator.connection?.saveData) return null;
    const layer = document.createElement('div');
    layer.className = 'backdrop-video';
    // The player's frame is made here, so it carries the autoplay permission from its first load: the
    // browser reads it then, and only with it does a tap on Trailer let the playing video take sound.
    const frame = document.createElement('iframe');
    const key = encodeURIComponent(youtubeKey);
    frame.src = `https://www.youtube-nocookie.com/embed/${key}?enablejsapi=1&autoplay=1&mute=1&loop=1&playlist=${key}`
        + `&controls=1&playsinline=1&rel=0&iv_load_policy=3&origin=${encodeURIComponent(location.origin)}`;
    frame.width = BACKDROP_VIDEO_WIDTH;
    frame.height = BACKDROP_VIDEO_HEIGHT;
    frame.title = 'Trailer';
    frame.tabIndex = -1;
    frame.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
    frame.allowFullscreen = true;
    layer.append(frame);
    backdrop.after(layer);
    const cover = () => {
        // A little more than covering, so the black bars of a trailer that is not quite 16:9 stay out
        // of sight. The same framing serves the trailer brought forward, which fills the hero with the
        // player at that size, so YouTube sends a stream to match, until the full frame is asked for.
        const scale = Math.max(layer.clientWidth / BACKDROP_VIDEO_WIDTH, layer.clientHeight / BACKDROP_VIDEO_HEIGHT) * FILL_CROP;
        layer.style.setProperty('--video-scale', String(scale));
        layer.style.setProperty('--video-fill-width', `${Math.round(BACKDROP_VIDEO_WIDTH * scale)}px`);
        layer.style.setProperty('--video-fill-height', `${Math.round(BACKDROP_VIDEO_HEIGHT * scale)}px`);
    };
    cover();
    new ResizeObserver(cover).observe(layer);
    const ready = loadYoutubeApi().then(YT => new Promise(resolve => {
        const player = new YT.Player(frame, {
            events: {
                onReady: () => {
                    player.mute();
                    player.playVideo();
                    resolve(player);
                },
                onStateChange: event => {
                    if (event.data === YT.PlayerState.PLAYING && !layer.classList.contains('visible')) {
                        // YouTube shows its title and controls for the first seconds of playing; on a
                        // phone the band is not blurred, so it waits until they are gone.
                        setTimeout(() => layer.classList.add('visible'), 5000);
                    }
                }
            }
        });
    })).catch(() => null);
    return { layer, ready };
}

// Brings the player behind the hero forward, in the whole of the hero, the navbar sliding out of its
// way, framed as it was behind, with two controls floating over its corner: the whole frame, with
// bars where the hero is not 16:9, or filling the hero again; and exit, which Escape does too. A phone turned to landscape
// meanwhile gets the trailer on the whole screen, and back in the hero when turned upright again:
// browsers take a fullscreen request only from a tap, so the player covers the screen itself, and
// asks for fullscreen besides where the browser grants it. On a phone, the band comes forward in
// place: the head on it gives way, the body below stays, and its Trailer button reads Exit trailer.
function bringForward(hero, behind, buttons) {
    behind.ready.then(player => {
        const frame = player.getIframe();
        buttons.forEach(button => { button.textContent = 'Exit trailer'; });
        hero.classList.add('trailer-playing');
        // The size switch is instant; from the next frame on, the whole frame and the fill ease into each other.
        requestAnimationFrame(() => requestAnimationFrame(() => hero.classList.add('trailer-settled')));
        frame.tabIndex = 0;
        // On from where it is, with sound; a browser that pauses rather than let the page unmute
        // (iOS) gets it playing on, muted, and YouTube's volume button gives the sound.
        player.unMute();
        player.setVolume(80);
        player.playVideo();
        setTimeout(() => {
            if (player.getPlayerState() !== YT.PlayerState.PLAYING) {
                player.mute();
                player.playVideo();
            }
        }, 700);

        const controls = document.createElement('div');
        controls.className = 'trailer-controls';
        const control = (icon, label) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'button trailer-control';
            button.innerHTML = icon;
            button.setAttribute('aria-label', label);
            button.title = label;
            controls.append(button);
            return button;
        };
        const frameToggle = control(ICON_FULL_FRAME, 'Show the whole frame');
        const exit = control(ICON_EXIT, 'Exit trailer');
        hero.append(controls);
        frameToggle.addEventListener('click', () => {
            const fullFrame = hero.classList.toggle('trailer-full-frame');
            frameToggle.innerHTML = fullFrame ? ICON_FILL : ICON_FULL_FRAME;
            frameToggle.title = fullFrame ? 'Fill the screen' : 'Show the whole frame';
            frameToggle.setAttribute('aria-label', frameToggle.title);
        });
        // A phone turned sideways: touch, landscape, and low, which a tablet in landscape is not.
        const landscape = matchMedia('(orientation: landscape) and (pointer: coarse) and (max-height: 500px)');
        const fitScreen = () => {
            hero.classList.toggle('trailer-fullscreen', landscape.matches);
            if (landscape.matches) frame.requestFullscreen?.().catch(() => {});
            else if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
        };
        landscape.addEventListener('change', fitScreen);
        fitScreen();
        const onKey = event => { if (event.key === 'Escape') sendBack(); };
        const sendBack = () => {
            landscape.removeEventListener('change', fitScreen);
            hero.classList.remove('trailer-fullscreen');
            if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
            player.mute();
            player.playVideo();
            // Paused, the player keeps its controls up: it is set playing again, and once more if the first
            // call did not take, as on a phone that stops a video losing its sound.
            [600, 1800].forEach(wait => setTimeout(() => { if (player.getPlayerState() !== YT.PlayerState.PLAYING) player.playVideo(); }, wait));
            // The player keeps its controls up while it has the focus: the focus goes back to the page.
            frame.tabIndex = -1;
            frame.blur();
            buttons[0]?.focus({ preventScroll: true });
            controls.remove();
            document.removeEventListener('keydown', onKey);
            hero.classList.remove('trailer-settled', 'trailer-playing', 'trailer-full-frame');
            buttons.forEach(button => { button.textContent = 'Trailer'; });
            delete hero.sendTrailerBack;
        };
        hero.sendTrailerBack = sendBack;
        exit.addEventListener('click', sendBack);
        document.addEventListener('keydown', onKey);
        exit.focus({ preventScroll: true });
    });
}

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
 * Appends an item of a window to the target. A movie listed in groups, under
 * the day it joined the catalogue or the month it was checked in, goes under
 * its group: the last group on the page when it is that one, a new group
 * otherwise, so a group that continues in the next window continues on the
 * page. Anything else is a card in a grid.
 */
function appendItem(kind, target, item) {
    if (kind !== 'grouped') {
        target.appendChild(renderCard(kind, item));
        return;
    }
    let group = target.lastElementChild;
    if (!group || group.dataset.group !== item.group) {
        group = document.createElement('section');
        group.className = 'day-group';
        group.dataset.group = item.group;
        const header = document.createElement('div');
        header.className = 'section-header';
        const title = document.createElement('h2');
        title.className = 'section-title';
        title.textContent = item.group;
        header.appendChild(title);
        group.appendChild(header);
        const grid = document.createElement('div');
        grid.className = 'movie-grid';
        group.appendChild(grid);
        target.appendChild(group);
    }
    group.querySelector('.movie-grid').appendChild(movieCard(item));
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
    cardTitles.observe(info);

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

// Every toggle answers with the state of both lists and the vote: checking a
// movie in takes it off the watchlist, so the other button follows, and
// the thumbs appear once the movie is watched. One button carries Watched and
// the check-ins; its menu tells having watched it some time ago, as history,
// from watching it on a day, a check-in, and unmarks it again.
function setupListToggles() {
    const watchlist = document.querySelector('[data-list-toggle="watchlist"]');
    const seen = document.querySelector('[data-seen]');
    const voteButtons = document.querySelectorAll('[data-vote]');
    const voteGroup = document.querySelector('[data-vote-buttons]');
    // The button reads Watched, pink once the movie is, and, checked in, the latest day in green with how
    // many more. The days come with the page, the latest first; a new one joins the top.
    const CHECK_IN_DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
    const checkInDays = (seen?.dataset.checkInDays || '').split('|').filter(Boolean);
    let watched = seen?.dataset.seen === 'true';
    const showSeen = () => {
        if (!seen) return;
        const count = checkInDays.length;
        seen.classList.toggle('checked-in', count > 0);
        seen.classList.toggle('active', watched && count === 0);
        seen.setAttribute('aria-pressed', String(watched));
        seen.querySelector('[data-seen-label]').textContent = count ? `Checked in ${checkInDays[0]}` : 'Watched';
        const more = seen.querySelector('[data-check-in-more]');
        more.hidden = count < 2;
        more.textContent = `+${count - 1}`;
        seen.title = !watched ? 'Watched some time ago, or on a day of the past week'
            : count === 0 ? 'Watched' : count === 1 ? 'Checked in once' : `Checked in ${count} times`;
    };
    const render = state => {
        if (watchlist) {
            watchlist.classList.toggle('active', state.onWatchlist);
            watchlist.setAttribute('aria-pressed', String(state.onWatchlist));
            watchlist.title = state.onWatchlist ? 'On your watchlist' : 'Add to your watchlist';
        }
        watched = state.seen;
        // Unmarked as watched, the check-ins go with it.
        if (!watched) checkInDays.length = 0;
        showSeen();
        if (voteGroup) voteGroup.hidden = !watched;
        voteButtons.forEach(button => button.classList.toggle('active', String(state.liked) === button.dataset.vote));
    };
    showSeen();
    const all = [...(watchlist ? [watchlist] : []), ...voteButtons, ...(seen ? [seen] : [])];
    const submit = async (url, body) => {
        all.forEach(button => button.disabled = true);
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: body ? { 'Content-Type': 'application/json' } : {},
                body: body ? JSON.stringify(body) : undefined,
            });
            if (!response.ok) return false;
            render(await response.json());
            return true;
        } finally {
            all.forEach(button => button.disabled = false);
        }
    };
    watchlist?.addEventListener('click', () => submit('/api/watchlist/' + watchlist.dataset.movieId));
    // The button opens its menu: the days of the past week, in the visitor's own calendar, with Some time
    // ago for a movie not yet watched, or Not watched after all for one that is; checked in more than
    // once, the other days first and a way to the days. The menu is placed on the page itself, under
    // the button, as the hero clips what sticks out of it; a click elsewhere, Escape or a scroll closes it.
    const menu = document.querySelector('[data-check-in-menu]');
    if (seen && menu) {
        const option = (text, attributes = {}, className = '') => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'share-option ' + className;
            Object.assign(button.dataset, attributes);
            button.textContent = text;
            return button;
        };
        const days = () => {
            menu.replaceChildren();
            for (let ago = 0; ago < 7; ago++) {
                const day = new Date();
                day.setDate(day.getDate() - ago);
                const key = [day.getFullYear(), String(day.getMonth() + 1).padStart(2, '0'), String(day.getDate()).padStart(2, '0')].join('-');
                menu.append(option(ago === 0 ? 'Today' : ago === 1 ? 'Yesterday' : WEEKDAY.format(day), { checkInDay: key }));
            }
            menu.append(watched
                ? option('Not watched after all', { seenToggle: '' }, 'share-option-apart share-option-muted')
                : option('Some time ago', { seenToggle: '' }, 'share-option-apart'));
        };
        const others = () => {
            menu.replaceChildren();
            checkInDays.slice(1).forEach(day => {
                const row = document.createElement('div');
                row.className = 'check-in-day';
                row.textContent = day;
                menu.append(row);
            });
            menu.append(option('Check in again', { checkInAgain: '' }, 'share-option-apart'));
            menu.append(option('Not watched after all', { seenToggle: '' }, 'share-option-muted'));
        };
        const close = () => { menu.hidden = true; };
        // Under the button, or above it where the screen ends below; placed again when its content changes.
        const place = () => {
            const at = seen.getBoundingClientRect();
            menu.style.position = 'fixed';
            menu.style.left = `${Math.round(at.left)}px`;
            const below = at.bottom + 8 + menu.offsetHeight <= window.innerHeight;
            menu.style.top = `${Math.round(below ? at.bottom + 8 : at.top - 8 - menu.offsetHeight)}px`;
        };
        const open = () => {
            if (checkInDays.length > 1) others(); else days();
            document.body.append(menu);
            menu.hidden = false;
            place();
        };
        seen.addEventListener('click', () => menu.hidden ? open() : close());
        menu.addEventListener('click', event => {
            if (event.target.closest('[data-check-in-again]')) { days(); place(); return; }
            const movie = '/api/seen/' + seen.dataset.movieId;
            if (event.target.closest('[data-seen-toggle]')) { close(); submit(movie + '?when=earlier'); return; }
            const chosen = event.target.closest('[data-check-in-day]');
            if (!chosen) return;
            close();
            submit(movie + '/check-in?day=' + chosen.dataset.checkInDay).then(ok => {
                if (!ok) return;
                checkInDays.unshift(CHECK_IN_DAY.format(new Date(chosen.dataset.checkInDay + 'T00:00:00Z')));
                showSeen();
            });
        });
        // The path rather than the target: Check in again replaces the menu's content, the clicked option with it.
        document.addEventListener('click', event => {
            const path = event.composedPath();
            if (!path.includes(menu) && !path.includes(seen)) close();
        });
        document.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
        window.addEventListener('scroll', close, { passive: true });
    }
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

// A button that opens and closes a panel elsewhere on the page, the seen
// page's IMDb import: the panel it controls shows while it is pressed.
function setupPanelToggles() {
    document.querySelectorAll('[data-panel-toggle]').forEach(button => {
        const panel = document.getElementById(button.getAttribute('aria-controls'));
        button.addEventListener('click', () => {
            const open = button.getAttribute('aria-expanded') !== 'true';
            button.setAttribute('aria-expanded', String(open));
            panel.hidden = !open;
        });
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

// The day of a check-in, as the tags and the movie page show it.
const SEEN_DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

// A day of the past week as the check-in menu and button name it: "Wednesday 21 Oct", "21 Oct".
const WEEKDAY = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'short' });
const SHORT_DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' });

// How many calendar days ago a moment was, in the visitor's own time zone.
function daysAgo(date) {
    const startOf = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
    return Math.round((startOf(new Date()) - startOf(date)) / 86_400_000);
}

// A seen label with the day of its check-in, "✓ 14 Mar 2021", or the label
// alone for history, whose day is not known.
function seenLabel(label, watchedAt) {
    return watchedAt ? label + ' ' + SEEN_DAY.format(new Date(watchedAt)) : label;
}

// The visitor's seen movies, by id: the day of a check-in, or null for history.
const seenMovies = new Map();

// The movies the visitor's friends have seen, by id: each friend's name, or null for one who keeps it private.
const friendsSeen = new Map();

// The movies members of the visitor's clubs have seen, by id: each member's name, or null, and their club.
const clubsSeen = new Map();

// Every list of movies tags the ones the visitor has seen, with the day of a
// check-in, or the checkmark alone for history: the seen movies come in one
// request, and the tags go on the cards and rows on the page, and on those the
// scroll adds later.
async function setupSeenTags() {
    try {
        const response = await fetch('/api/seen');
        if (!response.ok) return;
        const marks = await response.json();
        Object.entries(marks.seen).forEach(([id, watchedAt]) => seenMovies.set(id, watchedAt));
        Object.entries(marks.friends).forEach(([id, names]) => friendsSeen.set(id, names));
        Object.entries(marks.clubs).forEach(([id, members]) => clubsSeen.set(id, members));
    } catch (e) {
        return;
    }
    tagSeenMovies();
    showSeenCount();
    new MutationObserver(tagSeenMovies).observe(document.body, { childList: true, subtree: true });
}

function tagSeenMovies() {
    document.querySelectorAll('a.movie-card, a.ranked-row').forEach(tagSeenMovie);
}

// Puts the tag on a card or row: cyan for a movie the visitor has watched, with
// the day of a check-in; yellow for one friends have watched, naming the friend
// or counting them; pink for one only club members have watched, likewise;
// none otherwise. The tag follows a mark or unmark.
function tagSeenMovie(link) {
    const id = (link.getAttribute('href').match(/^\/movie\/(tt\d+)/) || [])[1];
    if (!id) return;
    const wanted = seenTag(id);
    const existing = link.querySelector('.seen-tag');
    link.classList.toggle('is-seen', seenMovies.has(id));
    if (existing && (!wanted || existing.textContent !== wanted.text)) existing.remove();
    if (!wanted || (existing && existing.isConnected)) return;
    const tag = document.createElement('span');
    tag.className = 'seen-tag' + (wanted.kind ? ' ' + wanted.kind + '-tag' : '');
    tag.textContent = wanted.text;
    tag.title = wanted.title;
    (link.querySelector('.ranked-sub') || link.querySelector('.poster')).appendChild(tag);
}

// The tag a movie gets, or null: the visitor's own seen tag first, else the
// friends' marker, else the clubs'.
function seenTag(id) {
    if (seenMovies.has(id)) {
        return { text: seenLabel('✓', seenMovies.get(id)), title: seenLabel('Watched', seenMovies.get(id)), kind: null };
    }
    const friends = friendsSeen.get(id);
    if (friends) {
        const named = friends.filter(name => name);
        const others = friends.length - named.length;
        const who = friends.length === 1 ? (named[0] ?? 'A friend') : friends.length + ' friends';
        const all = [...named, ...(others === 1 ? ['a friend'] : others > 1 ? [others + ' friends'] : [])];
        return { text: '✓ ' + who, title: 'Watched by ' + all.join(', '), kind: 'friend' };
    }
    const members = clubsSeen.get(id);
    if (!members) return null;
    return { text: '✓ ' + clubTagText(members), title: 'Watched in ' + clubTagTitle(members), kind: 'club' };
}

// A club tag: the member's name, else their club's, for one; the count, with
// the club when they all share it, for several.
function clubTagText(members) {
    if (members.length === 1) return members[0].member ?? members[0].club;
    const clubs = new Set(members.map(seen => seen.club));
    return members.length + (clubs.size === 1 ? ' in ' + members[0].club : ' in your clubs');
}

// The tag's tooltip, per club: "Office: Alice, Bob, a member; Thursday: Carol".
function clubTagTitle(members) {
    const byClub = new Map();
    members.forEach(seen => byClub.set(seen.club, [...(byClub.get(seen.club) ?? []), seen.member]));
    return [...byClub].map(([club, names]) => {
        const named = names.filter(name => name);
        const others = names.length - named.length;
        return club + ': ' + [...named, ...(others === 1 ? ['a member'] : others > 1 ? [others + ' members'] : [])].join(', ');
    }).join('; ');
}

// The catch-up page: a tap on a movie marks it seen as history, or unmarks
// it, instead of opening it, and the tag follows at once; a movie tapped stays
// in place, so a tap can be undone. A search finds one movie to mark the same
// way, the genre and "Hide marked" filter the list on the server, and the
// count says how many are seen.
function setupCatchUp() {
    const list = document.querySelector('[data-catch-up-list]');
    if (!list) return;
    document.addEventListener('click', async event => {
        const card = event.target.closest('[data-catch-up] a.movie-card');
        if (!card) return;
        event.preventDefault();
        const id = card.getAttribute('href').split('/').pop();
        card.classList.add('marking');
        try {
            const response = await fetch('/api/seen/' + id + '?when=earlier', { method: 'POST' });
            if (!response.ok) return;
            const state = await response.json();
            if (state.seen) seenMovies.set(id, state.seenAt ?? null); else seenMovies.delete(id);
            tagSeenMovies();
            showSeenCount();
            // A visitor who is not signed in has something to lose after a few marks: the sign-in note stands out.
            document.querySelector('[data-signin-nudge]')?.classList.toggle('urgent', seenMovies.size >= SIGN_IN_NUDGE_MARKS);
        } finally {
            card.classList.remove('marking');
        }
    });

    // The genre and "Hide marked" are filters of the list, so changing one loads it anew.
    const genre = document.querySelector('[data-catch-up-genre]');
    const hideMarked = document.querySelector('[data-hide-marked]');
    const reload = () => {
        const filters = [];
        if (genre.value) filters.push('genre=' + encodeURIComponent(genre.value));
        if (hideMarked.checked) filters.push('unmarked');
        location.href = '/seen/catch-up' + (filters.length ? '?' + filters.join('&') : '');
    };
    genre.addEventListener('change', reload);
    hideMarked.addEventListener('change', reload);

    const search = document.querySelector('[data-catch-up-search]');
    const found = document.querySelector('[data-catch-up-found]');
    let timer;
    search.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(async () => {
            const query = search.value.trim();
            found.replaceChildren();
            found.hidden = !query;
            if (!query) return;
            const response = await fetch('/api/search/movies?query=' + encodeURIComponent(query));
            if (!response.ok || search.value.trim() !== query) return;
            (await response.json()).items.forEach(item => found.appendChild(movieCard(item)));
        }, 250);
    });
}

// After this many marks, the catch-up page's sign-in note stands out.
const SIGN_IN_NUDGE_MARKS = 5;

// The number of movies the visitor has seen, where a page shows it.
function showSeenCount() {
    document.querySelectorAll('[data-seen-count]').forEach(count => count.textContent = seenMovies.size.toLocaleString('en-GB'));
}

// A form's Save button lights up once something on it changed, and is grey while there is nothing to
// save: the page comes back from a save with nothing changed, so it is grey again.
function setupSaveButtons() {
    document.querySelectorAll('form[data-saves]').forEach(form => {
        const button = form.querySelector('button[type="submit"]:not([name])');
        if (!button) return;
        const snapshot = () => new URLSearchParams(new FormData(form)).toString();
        const saved = snapshot();
        const update = () => {
            const changed = snapshot() !== saved;
            button.disabled = !changed;
            button.classList.toggle('button-primary', changed);
        };
        form.addEventListener('input', update);
        form.addEventListener('change', update);
        update();
    });
}

// The open invites under the invite buttons: fetched again from the page after a new invite, so it shows
// in the list; and each one's link copied by its button, in full, as the device shares it.
async function refreshOpenInvites() {
    const list = document.querySelector('[data-open-invites]');
    if (!list) return;
    const response = await fetch(location.href);
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[data-open-invites]');
    if (fresh) list.replaceWith(fresh);
}

// The movies in common with a person unfold under them, fetched the first time, as a row of cards.
document.addEventListener('click', async event => {
    const button = event.target.closest('[data-in-common-movies]');
    if (!button) return;
    const row = button.closest('.in-common-row');
    let list = row.querySelector('.in-common-movies');
    if (list) {
        list.hidden = !list.hidden;
        button.textContent = list.hidden ? 'Show movies' : 'Hide movies';
        return;
    }
    button.disabled = true;
    try {
        const response = await fetch(button.dataset.inCommonMovies);
        if (!response.ok) return;
        const { items } = await response.json();
        list = document.createElement('div');
        list.className = 'movie-row in-common-movies';
        items.forEach(item => list.append(renderCard('movie', item)));
        row.append(list);
        button.textContent = 'Hide movies';
    } finally {
        button.disabled = false;
    }
});

document.addEventListener('click', async event => {
    const button = event.target.closest('[data-copy-link]');
    if (!button || !navigator.clipboard) return;
    await navigator.clipboard.writeText(new URL(button.dataset.copyLink, location.origin).href);
    const label = button.textContent;
    button.textContent = 'Copied';
    setTimeout(() => { button.textContent = label; }, 1500);
});

// The invite buttons on the friends page and a club's page: each asks its
// endpoint for a link, a personal invite, the public one, or a new public one,
// then hands it to the device's share sheet where there is one, under the
// title the page gives, and otherwise copies it; either way the link shows in
// the field, ready to paste into a message.
function setupInvite() {
    const buttons = document.querySelectorAll('[data-invite]');
    const field = document.querySelector('[data-invite-link]');
    buttons.forEach(button => button.addEventListener('click', async () => {
        button.disabled = true;
        try {
            const response = await fetch(button.dataset.invite, { method: 'POST' });
            if (!response.ok) return;
            const { url } = await response.json();
            field.value = url;
            field.hidden = false;
            field.select();
            refreshOpenInvites();
            if (navigator.share) {
                try {
                    await navigator.share({ title: button.closest('[data-invite-title]').dataset.inviteTitle, url });
                } catch (e) {
                    // The sheet was dismissed; the link stays in the field.
                }
            } else if (navigator.clipboard) {
                await navigator.clipboard.writeText(url);
            }
        } finally {
            button.disabled = false;
        }
    }));
}

// A picker: a box that suggests people or movies as a name is typed, like the search box, and on a
// choice keeps the choice's id in its hidden field and its name in the box. The box tells its
// form with a "pick" event. Typing again clears the choice, so a form never sends a name that was
// not picked. [data-picker] says what it picks: "person" or "movie".
function setupPickers() {
    document.querySelectorAll('[data-picker]').forEach(picker => {
        const kind = picker.dataset.picker;
        const input = picker.querySelector('[data-picker-input]');
        const value = picker.querySelector('[data-picker-value]');
        const panel = picker.querySelector('[data-picker-suggestions]');
        let timer = null;
        let active = -1;

        const options = () => Array.from(panel.querySelectorAll('.suggestion'));
        const hide = () => { panel.hidden = true; active = -1; };
        const setActive = index => {
            options().forEach((option, i) => option.classList.toggle('active', i === index));
            active = index;
        };
        const choose = option => {
            value.value = option.dataset.id;
            input.value = option.dataset.name;
            hide();
            picker.dispatchEvent(new CustomEvent('pick', { bubbles: true, detail: { id: option.dataset.id, name: option.dataset.name } }));
        };

        input.addEventListener('input', () => {
            value.value = '';
            clearTimeout(timer);
            const query = input.value.trim();
            if (query.length < 2) { hide(); return; }
            timer = setTimeout(async () => {
                const response = await fetch('/api/search/suggestions?query=' + encodeURIComponent(query));
                if (!response.ok || input.value.trim() !== query) return;
                const suggestions = await response.json();
                const items = kind === 'person'
                    ? suggestions.persons.map(p => ({ id: p.id, name: p.name, year: null, image: '/api/photo/' + p.id }))
                    : suggestions.movies.map(m => ({ id: m.id, name: m.title, year: m.year, image: '/api/poster/' + m.id }));
                panel.replaceChildren(...items.map(item => {
                    const option = suggestionLink('#', kind, kind === 'person' ? 'Person' : 'Movie', item.name, item.year, item.image);
                    option.dataset.id = item.id;
                    option.dataset.name = item.name;
                    option.addEventListener('mousedown', event => { event.preventDefault(); choose(option); });
                    return option;
                }));
                panel.hidden = items.length === 0;
                active = -1;
            }, SUGGESTION_DEBOUNCE_MS);
        });

        input.addEventListener('keydown', event => {
            const list = options();
            if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && !panel.hidden && list.length) {
                event.preventDefault();
                const step = event.key === 'ArrowDown' ? 1 : -1;
                setActive(((active === -1 ? (step === 1 ? -1 : list.length) : active) + step + list.length) % list.length);
            } else if (event.key === 'Enter' && !panel.hidden && list.length) {
                event.preventDefault();
                choose(list[Math.max(active, 0)]);
            } else if (event.key === 'Escape') {
                hide();
            }
        });
        input.addEventListener('blur', () => setTimeout(hide, 100));
    });

    // Six degrees asks as soon as both actors are picked; until then Connect asks for the one missing.
    document.querySelectorAll('[data-degrees-form]').forEach(form => {
        const missing = () => Array.from(form.querySelectorAll('[data-picker]')).find(p => !p.querySelector('[data-picker-value]').value);
        form.addEventListener('pick', () => { if (!missing()) form.submit(); });
        form.addEventListener('submit', event => {
            const picker = missing();
            if (!picker) return;
            event.preventDefault();
            picker.querySelector('[data-picker-input]').focus();
        });
    });
}

// Lists. On a movie's page, the Lists button opens a menu of the visitor's lists, fetched when it
// opens: a tap on one puts the movie on it or takes it off, and the tick follows; the last option
// starts a new list with the movie on it. On a list's page, a movie picked in the box goes on the
// list and the page reloads to show it; the × on a card takes it off.
function setupLists() {
    const button = document.querySelector('[data-list-menu]');
    if (button) {
        const movieId = button.dataset.listMenu;
        const menu = button.parentElement.querySelector('[data-list-menu-options]');
        const option = (label, className) => {
            const element = document.createElement('button');
            element.type = 'button';
            element.className = 'share-option ' + (className || '');
            element.textContent = label;
            return element;
        };
        button.addEventListener('click', async () => {
            if (!menu.hidden) { menu.hidden = true; return; }
            const response = await fetch('/api/lists/choices?movie=' + encodeURIComponent(movieId));
            if (!response.ok) return;
            const choices = await response.json();
            menu.replaceChildren(...choices.map(choice => {
                const element = option(choice.name, 'list-choice' + (choice.contains ? ' on-list' : ''));
                if (choice.club) {
                    const club = document.createElement('span');
                    club.className = 'list-choice-club';
                    club.textContent = choice.club;
                    element.append(club);
                }
                element.addEventListener('click', async () => {
                    const toggled = await fetch('/api/lists/' + choice.id + '/movies/' + encodeURIComponent(movieId), { method: 'POST' });
                    if (toggled.ok) element.classList.toggle('on-list', (await toggled.json()).onList);
                });
                return element;
            }));
            const create = document.createElement('a');
            create.className = 'share-option' + (choices.length ? ' share-option-apart' : '');
            create.href = '/lists?create&movie=' + encodeURIComponent(movieId);
            create.textContent = 'New list…';
            menu.append(create);
            // Fixed on the page's body, under the button, or over it where there is no room below:
            // the movie's hero clips what reaches out of it.
            document.body.append(menu);
            menu.hidden = false;
            const at = button.getBoundingClientRect();
            menu.style.position = 'fixed';
            menu.style.left = `${Math.round(Math.min(at.left, window.innerWidth - menu.offsetWidth - 8))}px`;
            const below = at.bottom + 8 + menu.offsetHeight <= window.innerHeight;
            menu.style.top = `${Math.round(below ? at.bottom + 8 : at.top - 8 - menu.offsetHeight)}px`;
        });
        document.addEventListener('click', event => {
            const path = event.composedPath();
            if (!path.includes(button) && !path.includes(menu)) menu.hidden = true;
        });
        window.addEventListener('scroll', () => { menu.hidden = true; }, { passive: true });
    }

    document.querySelectorAll('[data-list-add]').forEach(picker => picker.addEventListener('pick', async event => {
        const response = await fetch(picker.dataset.listAdd + encodeURIComponent(event.detail.id), { method: 'POST' });
        // Picking a movie already on the list takes it off, as the toggle does; put it back, as the box adds.
        if (response.ok && !(await response.json()).onList) await fetch(picker.dataset.listAdd + encodeURIComponent(event.detail.id), { method: 'POST' });
        location.reload();
    }));

    document.addEventListener('click', async event => {
        const remove = event.target.closest('[data-list-remove]');
        if (!remove) return;
        remove.disabled = true;
        const response = await fetch(remove.dataset.listRemove, { method: 'POST' });
        if (response.ok) remove.closest('.list-item').remove();
        else remove.disabled = false;
    });

    document.querySelectorAll('[data-confirm-remove]').forEach(form => form.addEventListener('submit', event => {
        if (!confirm('Remove this list, and the movies on it?')) event.preventDefault();
    }));
}

// The games: a movie picked from the suggestions is the move, the quiz's guess or the chain's next
// movie, sent at once; the quiz's score shares through the device's share sheet where there is one,
// and is copied otherwise.
function setupQuiz() {
    document.querySelectorAll('[data-pick-submits]').forEach(form => form.addEventListener('pick', () => form.submit()));
    document.querySelectorAll('[data-quiz-share]').forEach(button => button.addEventListener('click', async () => {
        const text = button.dataset.quizShare + '\n' + location.origin + '/play/quiz';
        if (navigator.share) {
            try { await navigator.share({ text }); } catch (e) { /* The sheet was dismissed. */ }
        } else if (navigator.clipboard) {
            await navigator.clipboard.writeText(text);
            button.textContent = 'Copied';
            setTimeout(() => { button.textContent = 'Share your score'; }, 1500);
        }
    }));
}

// What plays in the visitor's country's cinemas now: a popcorn tag in the corner of every poster of
// one, and of the movie on its own page, and the home page's row of them, kept to the visitor's
// taste. Fetched once a page has loaded; the tags follow the cards a scroll adds.
async function setupCinema() {
    let cinema;
    try {
        const response = await fetch('/api/cinema');
        if (!response.ok) return;
        cinema = await response.json();
    } catch (e) {
        return;
    }
    if (cinema.movies.length === 0) return;
    const playing = new Set(cinema.movies.map(movie => movie.id));
    const label = 'In cinemas' + (cinema.country ? ' in ' + cinema.country : '');
    const tag = () => {
        document.querySelectorAll('a.movie-card:not([data-cinema-asked])').forEach(card => {
            card.dataset.cinemaAsked = '';
            const id = (card.getAttribute('href').match(/^\/movie\/(tt\d+)/) || [])[1];
            const poster = card.querySelector('.poster');
            if (!id || !poster || !playing.has(id)) return;
            const mark = document.createElement('span');
            mark.className = 'cinema-tag';
            mark.textContent = '🍿';
            mark.title = label;
            poster.appendChild(mark);
        });
    };
    tag();
    new MutationObserver(tag).observe(document.body, { childList: true, subtree: true });

    const genres = document.querySelector('[data-cinema-movie]');
    if (genres && playing.has(genres.dataset.cinemaMovie)) {
        const chip = document.createElement('span');
        chip.className = 'genre-chip cinema-chip';
        chip.textContent = '🍿 ' + label;
        genres.prepend(chip);
    }

    const row = document.querySelector('[data-cinema-row]');
    if (row && cinema.picks.length > 0) {
        const movies = row.querySelector('[data-cinema-movies]');
        cinema.picks.forEach(movie => movies.append(renderCard('movie', movie)));
        if (cinema.country) row.querySelector('[data-cinema-country]').textContent = 'in ' + cinema.country;
        row.hidden = false;
    }
}
