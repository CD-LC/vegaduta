<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "register-commons.ftl" as registerCommons>
<#-- Native Keycloak registration (realm registrationAllowed=true). Simplified
     2026-09-24 on direct feedback ("so much gap in register page, research
     and simplify"): the "* Required fields" legend is gone
     (displayRequiredFields=false - every field here is required), the
     PatternFly grid gutters the user-profile macro emits are reset in
     login.css, first + last name share one row (CSS :has on #firstName /
     #lastName, so the realm's User Profile config stays in charge of WHICH
     fields render), the per-field "*" is hidden (every field here is
     required, so it was pure clutter - Keycloak still validates and shows
     the inline error), and password + confirm come LAST, after the profile
     fields, so the form reads identity -> name -> password. Keycloak's
     stock register.ftl splices them under the username field through the
     macro's "afterField" callback instead; the macro is called plain here
     (a supported, simpler equivalent - verified against the base theme in
     quay.io/keycloak/keycloak:25.0), which only changes where the password
     lands, never whether it is posted.

     Field ids/names (username, email, firstName, lastName, password,
     password-confirm, termsAccepted) and the form id kc-register-form are
     Keycloak's own; only layout/classes are ours. -->
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowRegister")}
    <#elseif section = "header">
        <#if messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${msg("aaiRegisterTitle")}
        </#if>
    <#elseif section = "subtitle">
        ${msg("aaiCreateAccountSubtitle")}
    <#elseif section = "form">
        <form id="kc-register-form" action="${url.registrationAction}" method="post">

            <@userProfileCommons.userProfileFormFields />

            <#if passwordRequired?? && passwordRequired>
            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="password" class="aai-label">${msg("password")}</label>
                <div class="aai-input-wrap">
                    <input type="password" id="password" class="${properties.kcInputClass!} aai-input" name="password"
                           autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                           aria-describedby="<#if messagesPerField.existsError('password')>input-error-password</#if>"
                    />
                    <button class="aai-eye" type="button" aria-label="${msg('showPassword')}"
                            aria-controls="password" data-password-toggle
                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                    </button>
                </div>
                <#if messagesPerField.existsError('password')>
                    <span id="input-error-password" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password'))?no_esc}
                    </span>
                </#if>
            </div>

            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="password-confirm" class="aai-label">${msg("passwordConfirm")}</label>
                <div class="aai-input-wrap">
                    <input type="password" id="password-confirm" class="${properties.kcInputClass!} aai-input" name="password-confirm"
                           autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
                           aria-describedby="<#if messagesPerField.existsError('password-confirm')>input-error-password-confirm</#if>"
                    />
                    <button class="aai-eye" type="button" aria-label="${msg('showPassword')}"
                            aria-controls="password-confirm" data-password-toggle
                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                    </button>
                </div>
                <#if messagesPerField.existsError('password-confirm')>
                    <span id="input-error-password-confirm" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                    </span>
                </#if>
            </div>
            </#if>

            <@registerCommons.termsAcceptance/>

            <#if recaptchaRequired?? && (recaptchaVisible!false)>
                <div class="${properties.kcFormGroupClass!}">
                    <div class="g-recaptcha" data-size="compact" data-sitekey="${recaptchaSiteKey}" data-action="${recaptchaAction}"></div>
                </div>
            </#if>

            <#if recaptchaRequired?? && !(recaptchaVisible!false)>
                <script>
                    function onSubmitRecaptcha(token) {
                        document.getElementById("kc-register-form").submit();
                    }
                </script>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit g-recaptcha"
                            data-sitekey="${recaptchaSiteKey}" data-callback='onSubmitRecaptcha' data-action='${recaptchaAction}' type="submit">
                        ${msg("aaiCreateAccount")}
                    </button>
                </div>
            <#else>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" type="submit" value="${msg("aaiCreateAccount")}"/>
                </div>
            </#if>

            <div class="aai-hint">
                <span>${msg("aaiHaveAccount")} <a href="${url.loginUrl}">${msg("doLogIn")}</a></span>
            </div>
        </form>
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    </#if>
</@layout.registrationLayout>
