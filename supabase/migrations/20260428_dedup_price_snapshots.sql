-- Remove existing duplicate snapshots (keep the earliest per product_id + tweet_url)
DELETE FROM public.price_snapshots
WHERE id IN (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY product_id, tweet_url
      ORDER BY captured_at ASC
    ) AS rn
    FROM public.price_snapshots
    WHERE tweet_url IS NOT NULL
  ) sub
  WHERE rn > 1
);

-- Unique constraint so duplicates are rejected at the DB level
CREATE UNIQUE INDEX IF NOT EXISTS price_snapshots_product_tweet_url_idx
  ON public.price_snapshots (product_id, tweet_url)
  WHERE tweet_url IS NOT NULL;
