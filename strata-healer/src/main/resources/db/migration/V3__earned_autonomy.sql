-- Earned autonomy (FR10): each action's run of successes and how many levels its autonomy has moved.
-- level_shift < 0: less cautious than its risk score gives (promoted); > 0: more cautious (demoted).
ALTER TABLE action_trust ADD COLUMN success_streak INTEGER NOT NULL DEFAULT 0;
ALTER TABLE action_trust ADD COLUMN level_shift INTEGER NOT NULL DEFAULT 0;
ALTER TABLE action_trust ADD CONSTRAINT action_trust_streak_check CHECK (success_streak >= 0);
