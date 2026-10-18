package ru.bluenet.vpn;

/** Bounded retries; never retries provisioning, profile/permission failures or ambiguous mutations. */
final class ReconnectPolicy {
    private int attempts;
    private long healthySince;
    void connected(long now) {
        if (healthySince == 0) healthySince = now;
        if (now - healthySince >= 60000) attempts = 0;
    }
    void reset() { attempts = 0; healthySince = 0; }
    long nextDelay(boolean requested, boolean enabled, boolean hasProfile, boolean retryable) {
        healthySince = 0;
        if (!requested || !enabled || !hasProfile || !retryable || attempts >= 5) return -1;
        return Math.min(30000, 2000L << attempts++);
    }
}
