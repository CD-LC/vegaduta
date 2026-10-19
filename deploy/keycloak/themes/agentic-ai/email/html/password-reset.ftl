<#-- Branded HTML email for the "forgot password" self-service reset flow.
     Standalone document (no <#import "template.ftl">) - see email-verification.ftl's
     header comment for why. Vars provided by Keycloak: link, linkExpiration (minutes),
     realmName, user.firstName/lastName/email. -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Reset your VegaDūta password</title>
</head>
<body style="margin:0;padding:0;background-color:#f4f3fb;font-family:Arial,Helvetica,sans-serif;">
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Reset the password for your VegaDūta account.</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f3fb;padding:32px 16px;">
<tr>
<td align="center">
<table role="presentation" width="480" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;width:100%;background-color:#ffffff;border-radius:12px;border:1px solid #e7e4f7;">
<tr>
<td style="padding:28px 32px 20px 32px;text-align:center;border-bottom:1px solid #efeaff;">
<img src="${properties.aaiLogoUrl!""}" alt="" width="38" height="28" style="display:inline-block;vertical-align:middle;border:0;margin:0 10px 0 0;">
<img src="${properties.aaiWordmarkUrl!""}" alt="VegaDūta" width="132" height="35" style="display:inline-block;vertical-align:middle;border:0;height:35px;width:132px;">
</td>
</tr>
<tr>
<td style="padding:32px 32px 8px 32px;color:#33303d;font-size:15px;line-height:1.6;">
<p style="margin:0 0 16px 0;">
<#if user.firstName?has_content>Hi ${user.firstName},<#else>Hi there,</#if>
</p>
<p style="margin:0 0 24px 0;">
We received a request to reset the password for your <strong>${realmName!'VegaDūta'}</strong> account. Click below to choose a new password.
</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px 0;">
<tr>
<td style="border-radius:8px;background-color:#5643e6;">
<a href="${link}" target="_blank" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">Reset password</a>
</td>
</tr>
</table>
<p style="margin:0 0 6px 0;font-size:13px;color:#6b6775;">
Button not working? Paste this link into your browser:
</p>
<p style="margin:0 0 4px 0;font-size:13px;word-break:break-all;">
<a href="${link}" target="_blank" style="color:#5643e6;">${link}</a>
</p>
<p style="margin:24px 0 0 0;font-size:13px;color:#6b6775;">
Didn't ask for this? You can safely ignore this email — your password won't change.
</p>
</td>
</tr>
<tr>
<td style="padding:20px 32px 28px 32px;border-top:1px solid #efeaff;text-align:center;">
<div style="font-size:12px;color:#6b6775;line-height:1.6;">
This link expires in ${linkExpiration} minutes.<br>
<span style="font-family:'Yatra One',Georgia,'Times New Roman',serif;font-weight:400;color:#33303d;letter-spacing:0.2px;">Vega<span style="color:#f97316;">Dūta</span></span> — AI staff for your business
</div>
</td>
</tr>
</table>
</td>
</tr>
</table>
</body>
</html>
