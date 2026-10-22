<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=false; section>
    <#if section = "header">
        ${msg("carRentalOtpTitle")}
    <#elseif section = "form">
        <p>${msg("carRentalOtpInstruction")}</p>
        <form id="car-rental-sms-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="otp" class="${properties.kcLabelClass!}">${msg("carRentalOtpLabel")}</label>
                <input id="otp" name="otp" type="text" inputmode="numeric" autocomplete="one-time-code"
                       pattern="[0-9]+" required autofocus class="${properties.kcInputClass!}" />
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button type="submit" name="action" value="verify"
                        class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}">${msg("carRentalOtpVerify")}</button>
                <button type="submit" name="action" value="resend" formnovalidate
                        class="${properties.kcButtonClass!} ${properties.kcButtonSecondaryClass!}">${msg("carRentalOtpResend")}</button>
            </div>
        </form>
        <#if smsChallengeId??>
            <p>${msg("carRentalOtpReference")}: <span id="sms-challenge-id">${smsChallengeId}</span></p>
        </#if>
    </#if>
</@layout.registrationLayout>
