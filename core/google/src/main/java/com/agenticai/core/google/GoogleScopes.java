package com.agenticai.core.google;

/**
 * The fixed scope set this platform requests on the base "Connect Google"
 * consent. Deliberately narrower than "full Drive/Gmail access" wherever
 * Google offers a narrower alternative: {@code drive.file} (only files this
 * app creates or the user explicitly opens with it, not the user's entire
 * Drive) and {@code gmail.send} (send-only, no read/delete of existing mail)
 * follow the same least-privilege instinct as this platform's other BYOK
 * integrations requesting the narrowest working credential.
 *
 * <p><b>Scope-combination rule</b>: Google's auth server rejects a single
 * authorization request that mixes certain scope families - {@code
 * drive.file} and {@code youtube.readonly} is one forbidden pair ({@code
 * Error 400: invalid_request} before the consent screen even renders), the
 * same rule that blocks Photos+Drive combinations. That is why {@link
 * #YOUTUBE_READONLY} and {@link #YOUTUBE_UPLOAD} are NOT in {@link
 * #REQUESTED} and are granted through their own incremental consents
 * instead - see their javadocs.
 */
public final class GoogleScopes {

    public static final String EMAIL = "openid https://www.googleapis.com/auth/userinfo.email";
    public static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    /**
     * Deliberately NOT in {@link #REQUESTED} (dropped 2026-10-09). It is a
     * Google <b>restricted</b> scope: publishing an OAuth client that requests
     * it needs a paid annual CASA security assessment, and while unpublished
     * every non-test-user gets {@code Error 403: access_denied} on the base
     * connect. Inbox reading isn't a product requirement yet, so the base
     * connect stays sensitive-scope-only; the Gmail tool's list/read actions
     * surface Google's 403 as a tool error for tenants without this grant.
     * Tenants that granted it before 2026-10-09 keep it.
     */
    public static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send";
    public static final String CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events";
    public static final String SHEETS = "https://www.googleapis.com/auth/spreadsheets";

    /**
     * Master-Roadmap-2026.md item #8 ("Content feedback loop"). Read-only,
     * covers both {@code channels.list(mine=true)} and {@code videos.list}/
     * {@code playlistItems.list} - enough for {@link
     * com.agenticai.core.google.YouTubeAnalyticsClient} to read the
     * connected account's own channel/video stats. Deliberately NOT {@code
     * yt-analytics.readonly} (the actual YouTube Analytics API scope, which
     * unlocks watch-time/traffic-source/audience-retention reports) - that
     * scope is on YouTube's "restricted scopes" list requiring a Google
     * security assessment before verification, a real compliance cost this
     * pass doesn't take on. {@code youtube.readonly} gets public-equivalent
     * stats (view/like/comment counts, upload list) that are already visible
     * to anyone on the channel's public page, which is why it doesn't carry
     * that same restricted-scope requirement - an honest "start with what's
     * cheap to ship" scope choice, not the full YouTube Analytics feature
     * set. See that client's javadoc for the exact data this unlocks.
     *
     * <p><b>Deliberately NOT in {@link #REQUESTED}</b>: Google's auth server
     * refuses to serve a consent screen for a request that combines {@code
     * drive.file} with any YouTube scope ({@code Error 400:
     * invalid_request}, "scopes that cannot be requested together") - while
     * this scope was bundled into the base connect, the ENTIRE Google
     * Workspace connect flow 400'd for every tenant. It is instead granted
     * via its own incremental consent: {@code GoogleOAuthController#callback}
     * chains straight into a {@code youtube.readonly}-only authorize URL
     * after every successful base connect that lacks it, so the end-state
     * grant is identical to the old bundle while each individual request
     * stays inside Google's combination rules. {@code
     * include_granted_scopes=true} (set on every authorize URL) folds the
     * already-granted base scopes into the incremental token's {@code
     * scope} string, and {@code GoogleOAuthService#connect} stores that
     * union verbatim - no merge logic needed.
     */
    public static final String YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly";

    public static final String REQUESTED = String.join(
        " ", EMAIL, DRIVE_FILE, GMAIL_SEND, CALENDAR_EVENTS, SHEETS
    );

    /**
     * Deliberately NOT part of {@link #REQUESTED}. Two reasons, not one:
     *
     * <ol>
     *   <li><b>It's a Google "sensitive scope"</b> - requesting it puts the
     *   whole OAuth client's consent screen in scope for Google's sensitive-
     *   scope verification (a brand-verification pass plus a submitted demo
     *   video showing the consent flow and how the scope is used, reviewed
     *   by a human at Google). That is a real, external, non-code process -
     *   see the rollout note below - and it must not silently degrade the
     *   consent screen every tenant sees just to connect Drive/Gmail/
     *   Calendar/Sheets, none of which need it.</li>
     *   <li><b>Least-privilege, same instinct as every other scope on this
     *   class</b>: publishing video to a real, public YouTube channel is a
     *   materially bigger grant than reading a channel's already-public
     *   stats ({@link #YOUTUBE_READONLY}), and the overwhelming majority of
     *   tenants who connect Google Workspace for Drive/Gmail/Calendar/Sheets
     *   have no interest in it at all.</li>
     * </ol>
     *
     * <p>Requested only via {@link GoogleOAuthService#youtubeUploadAuthorizeUrl()},
     * a separate, explicit "Enable YouTube publishing" consent the tenant
     * opts into - never bundled into the base connect. Google's standard
     * incremental-authorization flow (this class's {@code
     * include_granted_scopes=true}, already set on every authorize URL this
     * platform builds) means that consent adds this one scope on top of
     * whatever the tenant already granted, without re-prompting for the
     * rest; the token's returned {@code scope} string reflects the union,
     * which {@link GoogleOAuthService#connect} already stores verbatim into
     * {@code GoogleCredential#grantedScopes} with no code change needed here.
     *
     * <p><b>Rollout note (2026-08-30):</b> the code path that uses this scope
     * ({@code YouTubeUploadClient}, {@code UploadVideoToYouTubeToolCallback})
     * is built and gated on {@code GoogleCredential#hasScope(YOUTUBE_UPLOAD)},
     * but the scope will not actually grant for real users beyond a Google
     * Cloud Console "test user" allowlist until the sensitive-scope
     * verification above is submitted and approved - a business/compliance
     * step for the maintainer to run in Google Cloud Console, not something this
     * repo can complete on its own. Do not treat "code deployed" as "feature
     * live" for this one.
     */
    public static final String YOUTUBE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload";

    private GoogleScopes() {
    }
}
