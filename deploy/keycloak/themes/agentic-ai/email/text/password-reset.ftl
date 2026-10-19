<#-- Plain-text counterpart of html/password-reset.ftl, sent as the
     multipart/alternative text part for mail clients that prefer/require
     plain text. Keep this in sync with the HTML version's copy. -->
<#if user.firstName?has_content>Hi ${user.firstName},<#else>Hi there,</#if>

We received a request to reset the password for your ${realmName!'VegaDūta'} account. Use the link below to choose a new password:

${link}

This link expires in ${linkExpiration} minutes. Didn't ask for this? You can safely ignore this email — your password won't change.

VegaDūta
