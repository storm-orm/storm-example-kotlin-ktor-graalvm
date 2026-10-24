-- Recently viewed on the home page is the visitor's own: a view records the
-- account of a browser that came back with its account cookie. A first page,
-- and a crawler, which keeps no cookies, records no account, so they write no
-- account rows. The featured movie stays the site's latest view, and the
-- counts of the trending term and the day rows stay the site's, as before.
-- An account removed by a sign-in merge has its views moved first; were one
-- removed otherwise, its views would stay as the site's.
ALTER TABLE movie_view ADD COLUMN user_id VARCHAR REFERENCES app_user (id) ON DELETE SET NULL;

-- Only the views of returning visitors carry an account, a small share of the
-- log: the partial index holds those alone, in the order the home page reads.
CREATE INDEX idx_movie_view_user_viewed_at ON movie_view (user_id, viewed_at DESC) WHERE user_id IS NOT NULL;
