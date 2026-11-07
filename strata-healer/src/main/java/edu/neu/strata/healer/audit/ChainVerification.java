package edu.neu.strata.healer.audit;

/**
 * The result of checking the audit log's hash chain.
 *
 * @param valid     true when every entry checked out
 * @param entries   how many entries were checked
 * @param brokenAt  the seq of the first bad entry, or null when the chain is valid
 * @param reason    what was wrong with it (empty when valid)
 * @param headHash  the last good entry's hash ({@link HashChain#GENESIS_HASH} for an empty log). When
 *                  the chain is valid this is the newest entry's hash; recording it somewhere else (a
 *                  report, a ticket) makes later removal of the newest entries detectable too, which a
 *                  hash chain alone can't show.
 */
public record ChainVerification(boolean valid, long entries, Long brokenAt, String reason, String headHash) {

    public static ChainVerification ok(long entries, String headHash) {
        return new ChainVerification(true, entries, null, "", headHash);
    }

    public static ChainVerification broken(long entries, long brokenAt, String reason, String headHash) {
        return new ChainVerification(false, entries, brokenAt, reason, headHash);
    }
}
