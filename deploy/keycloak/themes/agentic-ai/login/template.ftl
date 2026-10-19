<#--
  VegaDūta hosted-auth shell - "paper & ink + glass" (2026-09-24).

  Shared by every page that calls <@layout.registrationLayout ...; section>:
  login, register, forgot-password, update-password, OTP, verify-email,
  update-profile, page-expired, error, info - plus any Keycloak page this
  theme does not override (WebAuthn, select-authenticator, terms ...), which
  inherits this macro through parent=keycloak. One centred glass card on a
  paper page, the PNG emblem + two-colour wordmark above it, a small footer
  below. Look and tokens: resources/css/login.css.

  Macro contract (keep every parameter - a caller passing an undeclared
  named argument makes FreeMarker throw and Keycloak falls back to its bare
  "We are sorry" page):
    bodyClass, displayInfo, displayMessage, displayRequiredFields (base)
    displayWide (this theme; login.ftl passes it - it no longer changes the
    card width, every page is the same 28rem, but it must stay declared).

  Sections a page may provide via "; section":
    "eyebrow"  - short DM Mono uppercase label above the title (optional)
    "header"   - the h1 text (required)
    "subtitle" - one muted line under the h1 (optional)
    "form"     - the body
    "info"     - text under the body, only rendered when displayInfo=true
    "show-username" - base contract for username-first flows
  Unknown sections render nothing, so "eyebrow"/"subtitle" are safe to omit;
  the wrapping element is <#compress>ed and hidden by CSS when empty.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false displayWide=false>
<#-- Captured once, up front, so <title> gets the page's own heading instead
     of the stock loginTitle key ("Sign in to ...") on every page. Safe to
     call "header" here and again below: none of the pages' "header"
     sections do anything but return message text. -->
<#assign aaiPageHeading><#compress><#nested "header"></#compress></#assign>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${(locale.currentLanguageTag)!'en'}">

<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <meta name="robots" content="noindex, nofollow">
    <title>${aaiPageHeading} - ${(realm.displayName)!'VegaDūta'}</title>
    <#-- Same small PNG favicons the Next.js app declares (web/public/favicon-*.png). -->
    <link rel="icon" type="image/png" sizes="32x32" href="${url.resourcesPath}/img/favicon-32.png" />
    <link rel="icon" type="image/png" sizes="16x16" href="${url.resourcesPath}/img/favicon-16.png" />
    <#-- Plus Jakarta Sans + DM Mono are self-hosted (@font-face in login.css);
         no third-party request may block first paint. -->
    <#if properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#-- Base-theme behaviour scripts (resolved through the parent chain):
         locale menu keyboard handling, and the "signed in from another tab"
         watcher Keycloak's own template ships. -->
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/node_modules/rfc4648/lib/rfc4648.js"
            }
        }
    </script>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        import { checkCookiesAndSetTimer } from "${url.resourcesPath}/js/authChecker.js";

        checkCookiesAndSetTimer(
          "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
</head>

<body class="aai-page ${properties.kcBodyClass!} ${bodyClass}">
  <div class="aai-wash" aria-hidden="true"></div>

  <main class="aai-shell">

    <#-- Brand: the winged emblem PNG + two-colour wordmark (ink "Vega", orange
         "Dūta") - the same lockup as web / Academy. Never a
         different mark. -->
    <a href="${properties.aaiAppUrl!""}/" class="aai-brand" aria-label="VegaDūta home">
      <img src="${url.resourcesPath}/img/logo-mark.png" alt="" class="aai-brand-mark" width="49" height="36">
      <span class="aai-wordmark">Vega<span class="aai-wordmark-accent">Dūta</span></span>
    </a>

    <div class="aai-card ${properties.kcFormCardClass!}" id="kc-card">

      <#if realm.internationalizationEnabled && locale.supported?size gt 1>
        <div class="${properties.kcLocaleMainClass!}" id="kc-locale">
          <div id="kc-locale-wrapper" class="${properties.kcLocaleWrapperClass!}">
            <div id="kc-locale-dropdown" class="menu-button-links ${properties.kcLocaleDropDownClass!}">
              <button id="kc-current-locale-link" aria-label="${msg("languages")}" aria-haspopup="true" aria-expanded="false" aria-controls="language-switch1">${locale.current}</button>
              <ul role="menu" tabindex="-1" aria-labelledby="kc-current-locale-link" aria-activedescendant="" id="language-switch1" class="${properties.kcLocaleListClass!}">
                <#assign i = 1>
                <#list locale.supported as l>
                  <li class="${properties.kcLocaleListItemClass!}" role="none">
                    <a role="menuitem" id="language-${i}" class="${properties.kcLocaleItemClass!}" href="${l.url}">${l.label}</a>
                  </li>
                  <#assign i++>
                </#list>
              </ul>
            </div>
          </div>
        </div>
      </#if>

      <header class="aai-card-head ${properties.kcFormHeaderClass!}">
        <div class="aai-eyebrow"><#compress><#nested "eyebrow"></#compress></div>
        <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
            <h1 id="kc-page-title">${aaiPageHeading}</h1>
            <p class="aai-subtitle"><#compress><#nested "subtitle"></#compress></p>
        <#else>
            <h1 id="kc-page-title">${aaiPageHeading}</h1>
            <p class="aai-subtitle"><#compress><#nested "subtitle"></#compress></p>
            <#nested "show-username">
            <div id="kc-username" class="${properties.kcFormGroupClass!} aai-user-chip">
                <span id="kc-attempted-username">${auth.attemptedUsername}</span>
                <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}" title="${msg("restartLoginTooltip")}">
                    <div class="kc-login-tooltip">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/></svg>
                        <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
                    </div>
                </a>
            </div>
        </#if>
        <#-- displayRequiredFields: the "* Required fields" legend is dropped on
             purpose - every field on these forms is required, so the legend
             was noise; the per-field "*" the user-profile macro prints stays,
             small and muted next to the label. -->
      </header>

      <div id="kc-content">
        <div id="kc-content-wrapper">

          <#-- Real Keycloak feedback (wrong password, expired action, ...).
               App-initiated actions should not see warnings about needing to
               complete the action during login (base-theme rule). -->
          <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
              <div class="alert-${message.type} aai-alert" role="<#if message.type = 'error'>alert<#else>status</#if>">
                  <#if message.type = 'success'>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m8.5 12 2.5 2.5 4.5-5"/></svg>
                  <#elseif message.type = 'warning'>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>
                  <#elseif message.type = 'error'>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
                  <#else>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
                  </#if>
                  <span class="kc-feedback-text">${kcSanitize(message.summary)?no_esc}</span>
              </div>
          </#if>

          <#nested "form">

          <#if auth?has_content && auth.showTryAnotherWayLink()>
              <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                  <div class="${properties.kcFormGroupClass!}">
                      <input type="hidden" name="tryAnotherWay" value="on"/>
                      <button type="submit" id="try-another-way">${msg("doTryAnotherWay")}</button>
                  </div>
              </form>
          </#if>

          <#nested "socialProviders">

          <#if displayInfo>
              <div id="kc-info" class="${properties.kcSignUpClass!}">
                  <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                      <#nested "info">
                  </div>
              </div>
          </#if>
        </div>
      </div>
    </div>

    <footer class="aai-foot">
      <span>${msg("aaiFootSecured")}</span>
      <span class="aai-foot-sep" aria-hidden="true">·</span>
      <a href="${properties.aaiPrivacyUrl!""}">${msg("aaiFootPrivacy")}</a>
      <span class="aai-foot-sep" aria-hidden="true">·</span>
      <a href="${properties.aaiTermsUrl!""}">${msg("aaiFootTerms")}</a>
      <span class="aai-foot-sep" aria-hidden="true">·</span>
      <a href="${properties.aaiHelpUrl!""}">${msg("aaiFootHelp")}</a>
    </footer>

  </main>
  <#-- Guard every form in the card against a double submit (only login.ftl's
       own form did this before, via its inline onsubmit). Disabling the
       submit control after the first submit is enough - the page is about to
       navigate away, so nothing needs to re-enable it. -->
  <script>
    document.querySelectorAll("#kc-content form").forEach(function (form) {
      form.addEventListener("submit", function () {
        form.querySelectorAll('button[type="submit"], input[type="submit"]').forEach(function (btn) {
          btn.disabled = true;
        });
      });
    });
  </script>
</body>
</html>
</#macro>
